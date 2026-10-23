package com.wgtunnelx.xray.engine

import android.app.PendingIntent
import com.wgtunnelx.xray.config.SessionParams

/** Everything [XrayVpnService] needs to bring up one routed tunnel. */
data class RoutingRequest(
    val tunnelId: Int,
    val sessionName: String,
    val params: SessionParams,
    val configJson: String,
    /** WireGuard `IncludedApplications`; when non-empty only these apps enter the VPN. */
    val includedApps: List<String> = emptyList(),
    /** WireGuard `ExcludedApplications`. */
    val excludedApps: List<String> = emptyList(),
    val metered: Boolean = false,
    val configureIntent: PendingIntent? = null,
)

sealed interface EngineState {
    data object Stopped : EngineState

    data class Starting(val tunnelId: Int) : EngineState

    data class Running(val tunnelId: Int, val sinceMillis: Long, val coreVersion: String) :
        EngineState

    data class Failed(val tunnelId: Int?, val message: String) : EngineState

    /** Another VPN took over or the user revoked the permission in system settings. */
    data class Revoked(val tunnelId: Int?) : EngineState
}

data class OutboundTraffic(val uplink: Long, val downlink: Long)
