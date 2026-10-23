package com.wgtunnelx.xray.geo

import android.content.Context
import com.wgtunnelx.xray.model.GeoOptions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class GeoKind(val fileName: String, val prefix: String) {
    IP("geoip.dat", "geoip:"),
    SITE("geosite.dat", "geosite:"),
}

data class GeoFileInfo(val sizeBytes: Long, val updatedAtMillis: Long, val codes: Set<String>)

data class GeoState(
    val geoIp: GeoFileInfo? = null,
    val geoSite: GeoFileInfo? = null,
    val downloading: GeoKind? = null,
    /** 0..1, or null when the size is unknown. */
    val progress: Float? = null,
    val lastError: String? = null,
) {
    fun info(kind: GeoKind): GeoFileInfo? =
        when (kind) {
            GeoKind.IP -> geoIp
            GeoKind.SITE -> geoSite
        }
}

/**
 * Owns `files/xray/geoip.dat` and `files/xray/geosite.dat`, the directory Xray is pointed at via
 * `xray.location.asset`. Downloads replace files atomically and only after the new file parsed as a
 * valid geo list, so a broken download never breaks a running configuration.
 */
class GeoDataManager(context: Context) {

    val assetDir: File = File(context.filesDir, "xray").apply { mkdirs() }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(GeoState())
    val state: StateFlow<GeoState> = _state.asStateFlow()

    suspend fun refreshState() =
        withContext(Dispatchers.IO) {
            val ip = loadInfo(GeoKind.IP)
            val site = loadInfo(GeoKind.SITE)
            _state.update { it.copy(geoIp = ip, geoSite = site) }
        }

    fun file(kind: GeoKind) = File(assetDir, kind.fileName)

    private fun codesFile(kind: GeoKind) = File(assetDir, kind.fileName + ".codes")

    private fun loadInfo(kind: GeoKind): GeoFileInfo? {
        val dat = file(kind)
        if (!dat.isFile || dat.length() == 0L) return null
        val cache = codesFile(kind)
        val codes =
            if (cache.isFile && cache.lastModified() >= dat.lastModified()) {
                cache.readLines().filter { it.isNotBlank() }.toSet()
            } else {
                runCatching { GeoCatalog.readCodes(dat).map { it.lowercase() }.toSet() }
                    .onSuccess { cache.writeText(it.sorted().joinToString("\n")) }
                    .onFailure { Timber.e(it, "Cannot read ${dat.name}") }
                    .getOrDefault(emptySet())
            }
        return GeoFileInfo(dat.length(), dat.lastModified(), codes)
    }

    /** Downloads both files from [options]. Returns the first failure, if any. */
    suspend fun updateAll(options: GeoOptions): Result<Unit> = mutex.withLock {
        val results =
            listOf(GeoKind.SITE to options.geoSiteUrl, GeoKind.IP to options.geoIpUrl).map {
                (kind, url) ->
                download(kind, url)
            }
        results.firstOrNull { it.isFailure } ?: Result.success(Unit)
    }

    suspend fun update(kind: GeoKind, url: String): Result<Unit> = mutex.withLock {
        download(kind, url)
    }

    private suspend fun download(kind: GeoKind, url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            _state.update { it.copy(downloading = kind, progress = 0f, lastError = null) }
            val tmp = File(assetDir, kind.fileName + ".part")
            val result = runCatching {
                require(url.startsWith("https://") || url.startsWith("http://")) {
                    "Invalid URL for ${kind.fileName}: '$url'"
                }
                fetch(url, tmp) { progress -> _state.update { it.copy(progress = progress) } }
                val codes = GeoCatalog.readCodes(tmp)
                if (codes.isEmpty()) throw IOException("${kind.fileName}: no categories found")
                val target = file(kind)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                codesFile(kind).writeText(codes.map { it.lowercase() }.sorted().joinToString("\n"))
                Timber.i("Updated ${kind.fileName}: ${target.length()} bytes, ${codes.size} codes")
            }
            tmp.delete()
            val info = loadInfo(kind)
            _state.update { state ->
                val withFile =
                    when (kind) {
                        GeoKind.IP -> state.copy(geoIp = info)
                        GeoKind.SITE -> state.copy(geoSite = info)
                    }
                withFile.copy(
                    downloading = null,
                    progress = null,
                    lastError =
                        result.exceptionOrNull()?.let { "${kind.fileName}: ${it.message ?: it}" },
                )
            }
            result.onFailure { Timber.e(it, "Failed to download ${kind.fileName}") }
        }

    private fun fetch(url: String, target: File, onProgress: (Float?) -> Unit) {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "WGTunnelX")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw IOException("Redirect without location from $current")
                    if (++redirects > 5) throw IOException("Too many redirects")
                    current = URL(current, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $current")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastReported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (read - lastReported > 512 * 1024) {
                                lastReported = read
                                onProgress(if (total > 0) read.toFloat() / total else null)
                            }
                        }
                        if (total > 0 && read != total) {
                            throw IOException("Incomplete download: $read of $total bytes")
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }
}
