package com.wgtunnelx.xray

import com.wgtunnelx.xray.config.SessionParams
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.model.DnsOptions
import com.wgtunnelx.xray.model.RoutingPresets
import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings
import java.io.File
import org.junit.Test

/**
 * Writes the generated configs to `$XRAY_DUMP_DIR` so CI can feed them to `xray run -test` (see
 * scripts/validate-xray-configs.sh). Does nothing when the variable is not set.
 */
class ConfigDumpTest {
    @Test
    fun dump() {
        val dir = System.getenv("XRAY_DUMP_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val params =
            SessionParams(
                socksPort = 1080,
                socksUser = "u",
                socksPass = "p",
                tunnelDns = listOf("10.2.0.1"),
                tunnelNetworks = listOf("10.2.0.0/24", "10.2.0.2/32"),
                tunnelHasIpv6 = true,
                packageUids = mapOf("org.example" to 10100),
            )
        // an IPv4-only tunnel (IPv6 refused) with Telegram installed (its app rule kept)
        val ipv4Only =
            params.copy(
                tunnelHasIpv6 = false,
                packageUids = params.packageUids + ("org.telegram.messenger" to 10200),
            )
        RoutingPresets.entries.forEach { preset ->
            val settings =
                RoutingSettings(defaultTarget = preset.defaultTarget, rules = preset.rules)
            File(dir, "preset-${preset.name}.json")
                .writeText(XrayConfigBuilder.build(settings, params))
            File(dir, "preset-${preset.name}-ipv4-only.json")
                .writeText(XrayConfigBuilder.build(settings, ipv4Only))
        }
        val everything =
            RoutingSettings(
                dns =
                    DnsOptions(
                        remote = "https://1.1.1.1/dns-query",
                        fakeDns = true,
                        hosts = mapOf("router.lan" to "192.168.1.1"),
                    ),
                rules =
                    listOf(
                        RoutingRule(
                            name = "all fields",
                            domains =
                                listOf(
                                    "domain:example.com",
                                    "full:www.example.org",
                                    "keyword:test",
                                    "regexp:\\.example$",
                                ),
                            ips = listOf("1.1.1.1", "10.0.0.0/8", "fc00::/7", "geoip:private"),
                            ports = "443,8000-9000",
                            network = "tcp,udp",
                            protocols = listOf("tls", "quic"),
                            packages = listOf("org.example"),
                        )
                    ),
            )
        File(dir, "all-fields.json").writeText(XrayConfigBuilder.build(everything, params))
    }
}
