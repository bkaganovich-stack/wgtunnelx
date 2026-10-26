package com.wgtunnelx.xray.model

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where matched traffic goes. Tags match the outbound tags in the generated Xray config. */
@Serializable
enum class RouteTarget(val tag: String) {
    @SerialName("proxy") TUNNEL("proxy"),
    @SerialName("direct") DIRECT("direct"),
    @SerialName("block") BLOCK("block");

    companion object {
        fun fromTag(tag: String?): RouteTarget? =
            when (tag?.trim()?.lowercase()) {
                "proxy",
                "tunnel" -> TUNNEL
                "direct",
                "bypass" -> DIRECT
                "block",
                "blocked",
                "reject" -> BLOCK
                else -> null
            }
    }
}

/** Xray `routing.domainStrategy`. */
@Serializable
enum class DomainStrategy(val xray: String) {
    @SerialName("AsIs") AS_IS("AsIs"),
    @SerialName("IPIfNonMatch") IP_IF_NON_MATCH("IPIfNonMatch"),
    @SerialName("IPOnDemand") IP_ON_DEMAND("IPOnDemand"),
}

/**
 * One routing rule, a 1:1 subset of an Xray `routing.rules[]` entry. Every non-empty field must
 * match (AND); values inside one field are alternatives (OR), exactly like Xray.
 *
 * - [domains]: `geosite:xx`, `domain:`, `full:`, `regexp:`, `keyword:`, `ext:file:tag` or a plain
 *   substring.
 * - [ips]: `geoip:xx`, CIDR or single address, `ext:file:tag`.
 * - [ports]: Xray port list, e.g. `53,443,1000-2000`.
 * - [network]: `tcp`, `udp` or `tcp,udp`.
 * - [protocols]: sniffed protocols: `http`, `tls`, `quic`, `bittorrent`.
 * - [packages]: Android package names; resolved to UIDs for the Xray `process` matcher.
 */
@Serializable
data class RoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val target: RouteTarget = RouteTarget.TUNNEL,
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val ports: String = "",
    val network: String = "",
    val protocols: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            domains.isEmpty() &&
                ips.isEmpty() &&
                ports.isBlank() &&
                network.isBlank() &&
                protocols.isEmpty() &&
                packages.isEmpty()
}

@Serializable
data class DnsOptions(
    /**
     * Resolver used for domains that leave through the tunnel. `tunnel` means "the first DNS server
     * of the WireGuard config, or 1.1.1.1 when it has none". Queries to it are sent through the
     * tunnel.
     */
    val remote: String = TUNNEL_DNS,
    /**
     * Resolver used for domains that go direct. `localhost` means the Android system resolver of
     * the underlying network, so ISP/captive portals and private DNS keep working.
     */
    val direct: String = "localhost",
    /** Answer A/AAAA with fake IPs so that routing by domain works even without sniffing. */
    val fakeDns: Boolean = false,
    /** Static host overrides, `domain -> ip[,ip]`. */
    val hosts: Map<String, String> = emptyMap(),
) {
    companion object {
        const val TUNNEL_DNS = "tunnel"
        const val FALLBACK_REMOTE_DNS = "1.1.1.1"
    }
}

@Serializable
data class GeoOptions(
    val source: GeoSource = GeoSource.RUNETFREEDOM,
    val customGeoIpUrl: String = "",
    val customGeoSiteUrl: String = "",
    /** 0 disables periodic updates. */
    val autoUpdateDays: Int = 7,
) {
    val geoIpUrl: String
        get() = if (source == GeoSource.CUSTOM) customGeoIpUrl.trim() else source.geoIpUrl

    val geoSiteUrl: String
        get() = if (source == GeoSource.CUSTOM) customGeoSiteUrl.trim() else source.geoSiteUrl
}

@Serializable
enum class GeoSource(val geoIpUrl: String, val geoSiteUrl: String) {
    /** v2fly categories plus Russian blocklists (ru-blocked, ru-available-only-inside, ...). */
    @SerialName("runetfreedom")
    RUNETFREEDOM(
        "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geoip.dat",
        "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geosite.dat",
    ),
    @SerialName("loyalsoldier")
    LOYALSOLDIER(
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat",
    ),
    @SerialName("v2fly")
    V2FLY(
        "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
        "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
    ),
    @SerialName("custom") CUSTOM("", ""),
}

/** Everything the user configures for split routing. Persisted as JSON. */
@Serializable
data class RoutingSettings(
    val version: Int = CURRENT_VERSION,
    /** Master switch. When off, tunnels start in the regular WG Tunnel VPN mode. */
    val enabled: Boolean = false,
    /** Ids of tunnels routing applies to; empty means every tunnel. */
    val tunnelIds: Set<Int> = emptySet(),
    /** Outbound for traffic no rule matched. */
    val defaultTarget: RouteTarget = RouteTarget.TUNNEL,
    val rules: List<RoutingRule> = RoutingPresets.RUSSIA_DIRECT.rules,
    val domainStrategy: DomainStrategy = DomainStrategy.AS_IS,
    /** Sniff TLS SNI / HTTP Host / QUIC so domain rules match connections made by IP. */
    val sniffing: Boolean = true,
    /** Route private and link-local networks directly. */
    val bypassLan: Boolean = true,
    /**
     * Refuse QUIC (UDP 443) that would go through the tunnel, so it falls back to TCP. Keeps UDP
     * off the tunnel proxy's UDP relay, which can crash the app (see XrayConfigBuilder).
     */
    val blockQuicInTunnel: Boolean = true,
    val dns: DnsOptions = DnsOptions(),
    val geo: GeoOptions = GeoOptions(),
    /** Xray log level: debug, info, warning, error, none. */
    val logLevel: String = "warning",
    /**
     * Advanced: a complete Xray JSON config used instead of the generated one. The placeholders
     * `{{SOCKS_PORT}}`, `{{SOCKS_USER}}`, `{{SOCKS_PASS}}` are replaced with the local tunnel
     * proxy; the config must contain a `tun` inbound.
     */
    val customConfig: String = "",
    val useCustomConfig: Boolean = false,
) {
    fun appliesTo(tunnelId: Int): Boolean =
        enabled && (tunnelIds.isEmpty() || tunnelId in tunnelIds)

    /** Brings settings saved by an older version up to [CURRENT_VERSION]. */
    fun migrated(): RoutingSettings {
        var s = this
        if (s.version < 2) s = s.withTelegramRule().copy(version = 2)
        return s
    }

    /**
     * Version 2 added [TelegramApps.RULE] to the Russian presets. Rules that started from one of
     * them get it at the same place; rules that already route a Telegram app, or were written by
     * hand, are left alone.
     */
    private fun withTelegramRule(): RoutingSettings {
        val hasTelegram =
            rules.any { r ->
                r.id == TelegramApps.RULE.id || r.packages.any { it in TelegramApps.PACKAGES }
            }
        if (hasTelegram) return this
        val anchor = rules.indexOfFirst { it.id in TELEGRAM_ANCHORS }
        if (anchor < 0) return this
        return copy(
            rules = rules.toMutableList().apply { add(anchor, TelegramApps.RULE) }
        )
    }

    companion object {
        const val CURRENT_VERSION = 2

        /** Preset rules the Telegram rule goes in front of (RUSSIA_DIRECT, RUSSIA_BLOCKED_ONLY). */
        private val TELEGRAM_ANCHORS = setOf("preset-ru-direct-ip", "preset-ru-blocked")
    }
}
