package com.wgtunnelx.xray.engine

import android.app.PendingIntent
import android.os.Bundle
import androidx.core.os.BundleCompat

/**
 * Messages between the app process ([XrayEngine]) and the Xray process ([XrayVpnService]), sent
 * with [android.os.Messenger] over the service binding.
 */
internal object XrayIpc {
    /** Binding action for the control channel (the system binds with `android.net.VpnService`). */
    const val ACTION_CONTROL = "com.wgtunnelx.xray.CONTROL"

    // app -> xray
    const val MSG_START = 1
    const val MSG_STOP = 2
    const val MSG_TRAFFIC = 3

    // xray -> app
    const val MSG_STATE = 10
    const val MSG_TRAFFIC_RESULT = 11

    private const val TUNNEL_ID = "tunnelId"
    private const val SESSION = "session"
    private const val MTU = "mtu"
    private const val CONFIG = "config"
    private const val INCLUDED = "included"
    private const val EXCLUDED = "excluded"
    private const val METERED = "metered"
    private const val CONFIGURE_INTENT = "configureIntent"
    private const val ASSET_DIR = "assetDir"

    private const val KIND = "kind"
    private const val MESSAGE = "message"
    private const val SINCE = "since"
    private const val VERSION = "version"

    private const val TAGS = "tags"
    private const val UP = "up"
    private const val DOWN = "down"

    /** What the Xray process needs to bring up one routed tunnel. */
    data class Start(
        val tunnelId: Int,
        val sessionName: String,
        val mtu: Int,
        val configJson: String,
        val includedApps: List<String>,
        val excludedApps: List<String>,
        val metered: Boolean,
        val configureIntent: PendingIntent?,
        val assetDir: String,
    )

    fun RoutingRequest.toStart(assetDir: String) =
        Start(
            tunnelId = tunnelId,
            sessionName = sessionName,
            mtu = params.mtu,
            configJson = configJson,
            includedApps = includedApps,
            excludedApps = excludedApps,
            metered = metered,
            configureIntent = configureIntent,
            assetDir = assetDir,
        )

    fun Start.toBundle() =
        Bundle().apply {
            putInt(TUNNEL_ID, tunnelId)
            putString(SESSION, sessionName)
            putInt(MTU, mtu)
            putString(CONFIG, configJson)
            putStringArrayList(INCLUDED, ArrayList(includedApps))
            putStringArrayList(EXCLUDED, ArrayList(excludedApps))
            putBoolean(METERED, metered)
            putParcelable(CONFIGURE_INTENT, configureIntent)
            putString(ASSET_DIR, assetDir)
        }

    fun Bundle.toStart(): Start {
        classLoader = Start::class.java.classLoader
        return Start(
            tunnelId = getInt(TUNNEL_ID),
            sessionName = getString(SESSION).orEmpty(),
            mtu = getInt(MTU),
            configJson = requireNotNull(getString(CONFIG)) { "no config" },
            includedApps = getStringArrayList(INCLUDED).orEmpty(),
            excludedApps = getStringArrayList(EXCLUDED).orEmpty(),
            metered = getBoolean(METERED),
            configureIntent =
                BundleCompat.getParcelable(this, CONFIGURE_INTENT, PendingIntent::class.java),
            assetDir = requireNotNull(getString(ASSET_DIR)) { "no asset dir" },
        )
    }

    fun EngineState.toBundle() =
        Bundle().apply {
            when (val s = this@toBundle) {
                EngineState.Stopped -> putString(KIND, "stopped")
                is EngineState.Starting -> {
                    putString(KIND, "starting")
                    putInt(TUNNEL_ID, s.tunnelId)
                }
                is EngineState.Running -> {
                    putString(KIND, "running")
                    putInt(TUNNEL_ID, s.tunnelId)
                    putLong(SINCE, s.sinceMillis)
                    putString(VERSION, s.coreVersion)
                }
                is EngineState.Failed -> {
                    putString(KIND, "failed")
                    s.tunnelId?.let { putInt(TUNNEL_ID, it) }
                    putString(MESSAGE, s.message)
                }
                is EngineState.Revoked -> {
                    putString(KIND, "revoked")
                    s.tunnelId?.let { putInt(TUNNEL_ID, it) }
                }
            }
        }

    fun Bundle.toEngineState(): EngineState {
        val id = if (containsKey(TUNNEL_ID)) getInt(TUNNEL_ID) else null
        return when (getString(KIND)) {
            "starting" -> EngineState.Starting(id ?: -1)
            "running" -> EngineState.Running(id ?: -1, getLong(SINCE), getString(VERSION).orEmpty())
            "failed" -> EngineState.Failed(id, getString(MESSAGE).orEmpty())
            "revoked" -> EngineState.Revoked(id)
            else -> EngineState.Stopped
        }
    }

    fun Map<String, OutboundTraffic>.toBundle() =
        Bundle().apply {
            val tags = keys.toList()
            putStringArray(TAGS, tags.toTypedArray())
            putLongArray(UP, tags.map { getValue(it).uplink }.toLongArray())
            putLongArray(DOWN, tags.map { getValue(it).downlink }.toLongArray())
        }

    fun Bundle.toTraffic(): Map<String, OutboundTraffic> {
        val tags = getStringArray(TAGS) ?: return emptyMap()
        val up = getLongArray(UP) ?: return emptyMap()
        val down = getLongArray(DOWN) ?: return emptyMap()
        if (up.size != tags.size || down.size != tags.size) return emptyMap()
        return tags.indices.associate { tags[it] to OutboundTraffic(up[it], down[it]) }
    }
}
