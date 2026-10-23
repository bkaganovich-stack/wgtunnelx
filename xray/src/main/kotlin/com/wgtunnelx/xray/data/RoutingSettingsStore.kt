package com.wgtunnelx.xray.data

import android.content.Context
import com.wgtunnelx.xray.model.RoutingSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persists [RoutingSettings] as JSON in `files/xray/routing.json`.
 *
 * Deliberately kept outside the WG Tunnel Room database: upstream owns that schema and its
 * migration numbers, and a fork-side migration would collide with every future upstream one.
 */
class RoutingSettingsStore(context: Context) {

    private val file = File(File(context.filesDir, "xray").apply { mkdirs() }, "routing.json")
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RoutingSettings> = _settings.asStateFlow()

    val current: RoutingSettings
        get() = _settings.value

    suspend fun update(transform: (RoutingSettings) -> RoutingSettings) = mutex.withLock {
        val next = transform(_settings.value)
        if (next == _settings.value) return@withLock
        withContext(Dispatchers.IO) { write(next) }
        _settings.value = next
    }

    private fun load(): RoutingSettings {
        if (!file.isFile) return RoutingSettings()
        return runCatching { JSON.decodeFromString(RoutingSettings.serializer(), file.readText()) }
            .onFailure { Timber.e(it, "Corrupt routing settings, using defaults") }
            .getOrDefault(RoutingSettings())
    }

    private fun write(settings: RoutingSettings) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(RoutingSettings.serializer(), settings))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
        }
    }
}
