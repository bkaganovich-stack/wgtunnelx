package com.wgtunnelx.xray.config

import com.wgtunnelx.xray.model.DnsOptions
import com.wgtunnelx.xray.model.DomainStrategy
import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns [RoutingSettings] into a complete Xray-core JSON config.
 *
 * Traffic path: Android TUN -> Xray `tun` inbound (with sniffing) -> routing rules ->
 * - `proxy`: SOCKS5 to the WG Tunnel userspace (netstack) proxy, i.e. into the WireGuard tunnel
 * - `direct`: plain sockets of this app, which is excluded from its own VPN, so the underlying
 *   network
 * - `block`: blackhole
 * - `dns-out`: Xray's DNS module, which hijacks every port 53 query
 */
object XrayConfigBuilder {

    const val TAG_TUN = "tun"
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS_OUT = "dns-out"
    const val TAG_DNS_REMOTE = "dns-remote"
    const val TAG_DNS_DIRECT = "dns-direct"

    const val PLACEHOLDER_PORT = "{{SOCKS_PORT}}"
    const val PLACEHOLDER_USER = "{{SOCKS_USER}}"
    const val PLACEHOLDER_PASS = "{{SOCKS_PASS}}"

    private val FAKE_IPV4_POOL = "198.18.0.0/15"
    private val FAKE_IPV6_POOL = "fc00::/18"

    /** Private, link-local and multicast ranges. Literal CIDRs so no geoip.dat is needed. */
    val LAN_CIDRS =
        listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "224.0.0.0/4",
            "255.255.255.255/32",
            "fc00::/7",
            "fe80::/10",
            "ff00::/8",
        )

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun build(settings: RoutingSettings, params: SessionParams): String {
        if (settings.useCustomConfig && settings.customConfig.isNotBlank()) {
            return applyPlaceholders(settings.customConfig, params)
        }
        return json.encodeToString(JsonObject.serializer(), buildObject(settings, params))
    }

    fun applyPlaceholders(template: String, params: SessionParams): String =
        template
            .replace(PLACEHOLDER_PORT, params.socksPort.toString())
            .replace(PLACEHOLDER_USER, params.socksUser)
            .replace(PLACEHOLDER_PASS, params.socksPass)

    fun buildObject(settings: RoutingSettings, params: SessionParams): JsonObject {
        val activeRules = settings.rules.filter { it.enabled && !it.isEmpty }
        val remoteDns = resolveRemoteDns(settings.dns, params)
        val directDns = settings.dns.direct.trim().ifEmpty { "localhost" }
        val fakeDns = settings.dns.fakeDns
        val domainStrategy =
            if (fakeDns && settings.domainStrategy == DomainStrategy.AS_IS) {
                // fake IPs carry no geo information, resolve when an IP rule has to be checked
                DomainStrategy.IP_IF_NON_MATCH
            } else settings.domainStrategy

        return buildJsonObject {
            putJsonObject("log") { put("loglevel", settings.logLevel.ifBlank { "warning" }) }
            putJsonObject("stats") {}
            putJsonObject("policy") {
                putJsonObject("levels") {
                    putJsonObject("0") {
                        put("handshake", 4)
                        put("connIdle", 300)
                        put("uplinkOnly", 1)
                        put("downlinkOnly", 1)
                    }
                }
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }
            putJsonArray("inbounds") { add(tunInbound(settings, params, fakeDns)) }
            putJsonArray("outbounds") {
                add(socksOutbound(params))
                add(freedomOutbound())
                add(
                    buildJsonObject {
                        put("tag", TAG_BLOCK)
                        put("protocol", "blackhole")
                    }
                )
                add(
                    buildJsonObject {
                        put("tag", TAG_DNS_OUT)
                        put("protocol", "dns")
                    }
                )
            }
            put("dns", dnsObject(settings, activeRules, remoteDns, directDns, params))
            if (fakeDns) {
                putJsonArray("fakedns") {
                    add(
                        buildJsonObject {
                            put("ipPool", FAKE_IPV4_POOL)
                            put("poolSize", 65535)
                        }
                    )
                    if (params.tunnelHasIpv6) {
                        add(
                            buildJsonObject {
                                put("ipPool", FAKE_IPV6_POOL)
                                put("poolSize", 65535)
                            }
                        )
                    }
                }
            }
            putJsonObject("routing") {
                put("domainStrategy", domainStrategy.xray)
                put("rules", routingRules(settings, activeRules, params))
            }
        }
    }

    internal fun resolveRemoteDns(dns: DnsOptions, params: SessionParams): String {
        val configured = dns.remote.trim()
        if (configured.isNotEmpty() && configured != DnsOptions.TUNNEL_DNS) return configured
        return params.tunnelDns.firstOrNull { it.isNotBlank() } ?: DnsOptions.FALLBACK_REMOTE_DNS
    }

    private fun tunInbound(
        settings: RoutingSettings,
        params: SessionParams,
        fakeDns: Boolean,
    ): JsonObject = buildJsonObject {
        put("tag", TAG_TUN)
        put("protocol", "tun")
        putJsonObject("settings") {
            put("name", "xray0")
            put("mtu", params.mtu)
            put("userLevel", 0)
        }
        if (settings.sniffing || fakeDns) {
            putJsonObject("sniffing") {
                put("enabled", true)
                putJsonArray("destOverride") {
                    if (settings.sniffing) {
                        add(JsonPrimitive("http"))
                        add(JsonPrimitive("tls"))
                        add(JsonPrimitive("quic"))
                    }
                    if (fakeDns) add(JsonPrimitive("fakedns"))
                }
                // Without fake DNS the app already resolved the right address through our DNS
                // module, so the sniffed domain is used for routing only.
                put("routeOnly", !fakeDns)
            }
        }
    }

    private fun socksOutbound(params: SessionParams): JsonObject = buildJsonObject {
        put("tag", TAG_PROXY)
        put("protocol", "socks")
        putJsonObject("settings") {
            putJsonArray("servers") {
                add(
                    buildJsonObject {
                        put("address", SessionParams.LOCALHOST)
                        put("port", params.socksPort)
                        putJsonArray("users") {
                            add(
                                buildJsonObject {
                                    put("user", params.socksUser)
                                    put("pass", params.socksPass)
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun freedomOutbound(): JsonObject = buildJsonObject {
        put("tag", TAG_DIRECT)
        put("protocol", "freedom")
        putJsonObject("streamSettings") {
            putJsonObject("sockopt") { put("domainStrategy", "UseIP") }
        }
    }

    private fun dnsObject(
        settings: RoutingSettings,
        rules: List<RoutingRule>,
        remoteDns: String,
        directDns: String,
        params: SessionParams,
    ): JsonObject {
        val directDomains = domainsFor(rules, RouteTarget.DIRECT)
        val tunnelDomains = domainsFor(rules, RouteTarget.TUNNEL)
        val defaultIsTunnel = settings.defaultTarget != RouteTarget.DIRECT
        return buildJsonObject {
            if (settings.dns.hosts.isNotEmpty()) {
                putJsonObject("hosts") {
                    settings.dns.hosts.forEach { (domain, value) ->
                        val ips = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (domain.isBlank() || ips.isEmpty()) return@forEach
                        if (ips.size == 1) put(domain.trim(), ips.first())
                        else put(domain.trim(), JsonArray(ips.map(::JsonPrimitive)))
                    }
                }
            }
            putJsonArray("servers") {
                if (settings.dns.fakeDns) add(JsonPrimitive("fakedns"))
                if (defaultIsTunnel) {
                    if (directDomains.isNotEmpty()) {
                        add(dnsServer(directDns, TAG_DNS_DIRECT, directDomains))
                    }
                    add(dnsServer(remoteDns, TAG_DNS_REMOTE, emptyList()))
                } else {
                    if (tunnelDomains.isNotEmpty()) {
                        add(dnsServer(remoteDns, TAG_DNS_REMOTE, tunnelDomains))
                    }
                    add(dnsServer(directDns, TAG_DNS_DIRECT, emptyList()))
                }
            }
            put("queryStrategy", if (params.tunnelHasIpv6) "UseIP" else "UseIPv4")
        }
    }

    private fun dnsServer(address: String, tag: String, domains: List<String>): JsonObject =
        buildJsonObject {
            put("address", address)
            put("tag", tag)
            if (domains.isNotEmpty()) {
                put("domains", JsonArray(domains.map(::JsonPrimitive)))
                put("skipFallback", true)
            }
        }

    /** Domains of rules whose only condition is the domain list, so DNS can follow them safely. */
    private fun domainsFor(rules: List<RoutingRule>, target: RouteTarget): List<String> =
        rules
            .filter { it.target == target && it.domains.isNotEmpty() }
            .flatMap { it.domains }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun routingRules(
        settings: RoutingSettings,
        rules: List<RoutingRule>,
        params: SessionParams,
    ): JsonArray = buildJsonArray {
        // every DNS query that reaches the TUN is answered by the DNS module
        add(
            buildJsonObject {
                putJsonArray("inboundTag") { add(JsonPrimitive(TAG_TUN)) }
                put("port", "53")
                put("outboundTag", TAG_DNS_OUT)
            }
        )
        add(rule(TAG_PROXY) { putJsonArray("inboundTag") { add(JsonPrimitive(TAG_DNS_REMOTE)) } })
        add(rule(TAG_DIRECT) { putJsonArray("inboundTag") { add(JsonPrimitive(TAG_DNS_DIRECT)) } })

        val tunnelOnly = params.tunnelNetworks.filter { it.isNotBlank() }.distinct()
        if (tunnelOnly.isNotEmpty()) {
            add(rule(TAG_PROXY) { put("ip", JsonArray(tunnelOnly.map(::JsonPrimitive))) })
        }
        if (settings.bypassLan) {
            add(rule(TAG_DIRECT) { put("ip", JsonArray(LAN_CIDRS.map(::JsonPrimitive))) })
        }

        rules.forEach { r -> toXrayRule(r, params)?.let { add(it) } }

        // explicit catch-all
        add(rule(settings.defaultTarget.tag) { put("network", "tcp,udp") })
    }

    internal fun toXrayRule(rule: RoutingRule, params: SessionParams): JsonObject? {
        val uids =
            rule.packages
                .mapNotNull { params.packageUids[it.trim()] }
                .distinct()
                .map { it.toString() }
        // an app-only rule whose apps are not installed must not turn into a catch-all
        if (rule.packages.isNotEmpty() && uids.isEmpty()) return null
        return rule(rule.target.tag) {
            if (rule.domains.isNotEmpty()) put("domain", strings(rule.domains))
            if (rule.ips.isNotEmpty()) put("ip", strings(rule.ips))
            if (rule.ports.isNotBlank()) put("port", rule.ports.replace(" ", ""))
            if (rule.network.isNotBlank()) put("network", rule.network.replace(" ", ""))
            if (rule.protocols.isNotEmpty()) put("protocol", strings(rule.protocols))
            if (uids.isNotEmpty()) put("process", JsonArray(uids.map(::JsonPrimitive)))
            if (rule.name.isNotBlank()) put("ruleTag", rule.name)
        }
    }

    private fun strings(values: List<String>): JsonArray =
        JsonArray(
            values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive)
        )

    private inline fun rule(outbound: String, block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            block()
            put("outboundTag", outbound)
        }
}
