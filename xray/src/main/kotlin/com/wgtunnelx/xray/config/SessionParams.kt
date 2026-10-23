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
) {
    companion object {
        const val DEFAULT_MTU = 1500
        const val LOCALHOST = "127.0.0.1"
    }
}
