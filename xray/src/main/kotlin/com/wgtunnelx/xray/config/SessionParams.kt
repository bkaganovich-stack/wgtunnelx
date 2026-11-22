package com.wgtunnelx.xray.config

/**
 * Runtime facts the generated Xray config depends on. Collected from the WireGuard config of the
 * tunnel being started and from the local SOCKS5 endpoint the WG Tunnel backend exposes in proxy
 * mode.
 */
data class SessionParams(
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    /** DNS servers from the WireGuard `[Interface] DNS =` line (IP literals only). */
    val tunnelDns: List<String> = emptyList(),
    /**
     * Networks that exist only inside the tunnel (peer AllowedIPs narrower than a default route,
     * plus the interface subnets). They are pinned to the tunnel before the LAN bypass applies.
     */
    val tunnelNetworks: List<String> = emptyList(),
    val tunnelHasIpv6: Boolean = false,
    val mtu: Int = DEFAULT_MTU,
    /** Package name to UID for rules that match apps. */
    val packageUids: Map<String, Int> = emptyMap(),
    /** Upstream strict split tunneling for this session, null when it is off. */
    val strictSplit: StrictSplit? = null,
) {
    companion object {
        const val DEFAULT_MTU = 1500
        const val LOCALHOST = "127.0.0.1"
    }
}

/**
 * WG Tunnel's strict split tunneling (5.9.0), by app UID. The VPN builder already keeps apps out of
 * the TUN, but an app can still bind a socket to the VPN network explicitly. Like upstream, a
 * connection whose owner cannot be identified (UID -1 or root) is refused as well.
 */
data class StrictSplit(val mode: Mode, val uids: Set<Int>) {
    enum class Mode {
        /** Only these UIDs may use the tunnel (WireGuard `IncludedApplications`). */
        INCLUDE,
        /** These UIDs may not use the tunnel (WireGuard `ExcludedApplications`). */
        EXCLUDE,
    }

    companion object {
        /** Owner names AndroidLibXrayLite reports when the lookup fails (-1) or for root (0). */
        val UNIDENTIFIED_OWNERS = listOf("-1", "0")
    }
}
