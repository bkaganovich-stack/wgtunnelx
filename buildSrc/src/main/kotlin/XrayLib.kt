import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Provides `xray/libs/libv2ray.aar`, the gomobile build of Xray-core from
 * https://github.com/2dust/AndroidLibXrayLite (WG Tunnel X).
 *
 * The release tag is pinned in `xray/libxray.version` and, optionally, its SHA-256 in
 * `xray/libxray.sha256`. A missing or outdated AAR is downloaded once; a locally built AAR without
 * a version stamp is used as is.
 */
object XrayLib {
    private const val RELEASE_URL =
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/%s/libv2ray.aar"

    fun ensure(project: Project): File {
        val dir = project.rootProject.file("xray")
        val aar = File(dir, "libs/libv2ray.aar")
        val stamp = File(dir, "libs/libv2ray.version")
        val version = File(dir, "libxray.version").readText().trim()
        val expectedSha =
            File(dir, "libxray.sha256").takeIf { it.isFile }?.readText()?.trim()?.lowercase()

        val upToDate = aar.isFile && (!stamp.isFile || stamp.readText().trim() == version)
        if (upToDate) return aar

        val url = RELEASE_URL.format(version)
        project.logger.lifecycle("WG Tunnel X: downloading libv2ray.aar $version")
        aar.parentFile.mkdirs()
        val tmp = File(aar.parentFile, "libv2ray.aar.part")
        download(url, tmp)
        val sha = sha256(tmp)
        if (expectedSha != null && expectedSha.isNotEmpty() && sha != expectedSha) {
            tmp.delete()
            throw GradleException("libv2ray.aar $version: SHA-256 $sha does not match $expectedSha")
        }
        if (!tmp.renameTo(aar)) throw GradleException("Cannot move $tmp to $aar")
        stamp.writeText(version)
        project.logger.lifecycle("WG Tunnel X: libv2ray.aar $version sha256=$sha")
        return aar
    }

    private fun download(url: String, target: File) {
        var current = URI(url).toURL()
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            try {
                when (val code = c.responseCode) {
                    in 300..399 -> {
                        current =
                            URI(current.toString()).resolve(c.getHeaderField("Location")).toURL()
                        return@repeat
                    }
                    200 -> {
                        c.inputStream.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        return
                    }
                    else -> throw GradleException("HTTP $code downloading $current")
                }
            } finally {
                c.disconnect()
            }
        }
        throw GradleException("Too many redirects downloading $url")
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
