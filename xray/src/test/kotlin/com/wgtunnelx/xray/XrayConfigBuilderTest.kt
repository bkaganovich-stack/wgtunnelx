package com.wgtunnelx.xray

import com.wgtunnelx.xray.config.RuleProblem
import com.wgtunnelx.xray.config.RuleValidator
import com.wgtunnelx.xray.config.SessionParams
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.data.RoutingSettingsStore
import com.wgtunnelx.xray.data.RuleCodec
import com.wgtunnelx.xray.geo.GeoCatalog
import com.wgtunnelx.xray.model.DnsOptions
import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingPresets
import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

    private val params =
        SessionParams(
            socksPort = 40000,
            socksUser = "u",
            socksPass = "p",
            tunnelDns = listOf("10.2.0.1"),
            tunnelNetworks = listOf("10.2.0.0/24"),
            tunnelHasIpv6 = false,
            packageUids = mapOf("org.telegram.messenger" to 10123),
        )

    private fun build(settings: RoutingSettings): JsonObject =
        Json.parseToJsonElement(XrayConfigBuilder.build(settings, params)).jsonObject

    private fun JsonObject.rules(): List<JsonObject> =
        this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.outbound(): String = this["outboundTag"]!!.jsonPrimitive.content

    @Test
    fun `socks outbound points at the local tunnel proxy`() {
        val config = build(RoutingSettings(enabled = true))
        val proxy = config["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("proxy", proxy["tag"]!!.jsonPrimitive.content)
        val server = proxy["settings"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("127.0.0.1", server["address"]!!.jsonPrimitive.content)
        assertEquals(40000, server["port"]!!.jsonPrimitive.content.toInt())
        val user = server["users"]!!.jsonArray.first().jsonObject
        assertEquals("u", user["user"]!!.jsonPrimitive.content)
        assertEquals("p", user["pass"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tun inbound sniffs for routing only`() {
        val tun = build(RoutingSettings()).let { it["inbounds"]!!.jsonArray.first().jsonObject }
        assertEquals("tun", tun["protocol"]!!.jsonPrimitive.content)
        val sniffing = tun["sniffing"]!!.jsonObject
        assertEquals("true", sniffing["routeOnly"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("http", "tls", "quic"),
            sniffing["destOverride"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `rule order is dns hijack, dns servers, tunnel networks, lan, user rules, catch all`() {
        val rules = build(RoutingSettings(rules = RoutingPresets.RUSSIA_DIRECT.rules)).rules()
        assertEquals("dns-out", rules[0].outbound())
        assertEquals("proxy", rules[1].outbound()) // dns-remote
        assertEquals("direct", rules[2].outbound()) // dns-direct
        assertEquals(
            listOf("10.2.0.0/24"),
            rules[3]["ip"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("proxy", rules[3].outbound())
        assertEquals("direct", rules[4].outbound()) // LAN
        assertEquals(
            "geosite:category-ru",
            rules[5]["domain"]!!.jsonArray.first().jsonPrimitive.content,
        )
        assertEquals("direct", rules[5].outbound())
        val last = rules.last()
        assertEquals("tcp,udp", last["network"]!!.jsonPrimitive.content)
        assertEquals("proxy", last.outbound())
    }

    @Test
    fun `default direct sends the catch all direct`() {
        val rules = build(RoutingSettings(defaultTarget = RouteTarget.DIRECT)).rules()
        assertEquals("direct", rules.last().outbound())
    }

    @Test
    fun `disabled and empty rules are skipped`() {
        val settings =
            RoutingSettings(
                bypassLan = false,
                rules =
                    listOf(
                        RoutingRule(name = "off", enabled = false, domains = listOf("a.com")),
                        RoutingRule(name = "empty"),
                        RoutingRule(name = "on", domains = listOf("b.com")),
                    ),
            )
        val userRules = build(settings).rules().filter { it.containsKey("ruleTag") }
        assertEquals(listOf("on"), userRules.map { it["ruleTag"]!!.jsonPrimitive.content })
    }

    @Test
    fun `app rules become uid process matchers and missing apps drop the rule`() {
        val settings =
            RoutingSettings(
                rules =
                    listOf(
                        RoutingRule(name = "tg", packages = listOf("org.telegram.messenger")),
                        RoutingRule(name = "ghost", packages = listOf("not.installed")),
                    )
            )
        val userRules = build(settings).rules().filter { it.containsKey("ruleTag") }
        assertEquals(1, userRules.size)
        assertEquals("10123", userRules[0]["process"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun `dns servers follow the routing`() {
        val dns =
            build(RoutingSettings(rules = RoutingPresets.RUSSIA_DIRECT.rules))["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        // direct domains are resolved by the direct resolver, everything else through the tunnel
        assertEquals("localhost", servers[0]["address"]!!.jsonPrimitive.content)
        assertTrue(
            servers[0]["domains"]!!.jsonArray.any {
                it.jsonPrimitive.content == "geosite:category-ru"
            }
        )
        assertEquals("10.2.0.1", servers[1]["address"]!!.jsonPrimitive.content)
        assertNull(servers[1]["domains"])
        assertEquals("UseIPv4", dns["queryStrategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `remote dns falls back to cloudflare without tunnel dns`() {
        val resolved =
            XrayConfigBuilder.resolveRemoteDns(DnsOptions(), params.copy(tunnelDns = emptyList()))
        assertEquals(DnsOptions.FALLBACK_REMOTE_DNS, resolved)
        assertEquals(
            "tcp://8.8.8.8",
            XrayConfigBuilder.resolveRemoteDns(DnsOptions(remote = "tcp://8.8.8.8"), params),
        )
    }

    @Test
    fun `fake dns adds pool and disables routeOnly`() {
        val config = build(RoutingSettings(dns = DnsOptions(fakeDns = true)))
        assertTrue(config.containsKey("fakedns"))
        val sniffing = config["inbounds"]!!.jsonArray.first().jsonObject["sniffing"]!!.jsonObject
        assertEquals("false", sniffing["routeOnly"]!!.jsonPrimitive.content)
        assertEquals(
            "IPIfNonMatch",
            config["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `custom config placeholders are replaced`() {
        val custom =
            """{"outbounds":[{"port":{{SOCKS_PORT}},"user":"{{SOCKS_USER}}","pass":"{{SOCKS_PASS}}"}]}"""
        val out =
            XrayConfigBuilder.build(
                RoutingSettings(useCustomConfig = true, customConfig = custom),
                params,
            )
        assertEquals("""{"outbounds":[{"port":40000,"user":"u","pass":"p"}]}""", out)
    }

    @Test
    fun `validator reports unknown categories and missing files`() {
        val rule =
            RoutingRule(
                name = "r",
                domains = listOf("geosite:youtube", "geosite:nope"),
                ips = listOf("geoip:ru"),
            )
        val problems = RuleValidator.validateRule(rule, setOf("youtube"), null)
        assertTrue(problems.contains(RuleProblem.UnknownCategory("r", "geosite.dat", "nope")))
        assertTrue(problems.contains(RuleProblem.MissingGeoFile("r", "geoip.dat")))
        assertEquals(2, problems.size)
    }

    @Test
    fun `validator checks ips ports and networks`() {
        val rule =
            RoutingRule(
                name = "r",
                ips = listOf("10.0.0.0/8", "fc00::/7", "300.1.1.1", "1.1.1.1/40"),
                ports = "53,443,1000-2000",
                network = "tcp,udp",
                protocols = listOf("tls", "ftp"),
            )
        val invalid =
            RuleValidator.validateRule(rule, emptySet(), emptySet()).map {
                (it as RuleProblem.InvalidValue).value
            }
        assertEquals(listOf("300.1.1.1", "1.1.1.1/40", "ftp"), invalid)
        assertTrue(
            RuleValidator.validateRule(RoutingRule(ports = "70000"), null, null).isNotEmpty()
        )
        assertFalse(RuleValidator.isIpOrCidr("example.com"))
    }

    @Test
    fun `presets only use categories present in the runetfreedom database`() {
        val known =
            setOf(
                "category-ru",
                "ru-available-only-inside",
                "ru-blocked",
                "youtube",
                "instagram",
                "facebook",
                "twitter",
                "discord",
                "telegram",
                "openai",
                "anthropic",
                "category-ads-all",
                "ru",
                "private",
            )
        RoutingPresets.entries.forEach { preset ->
            val problems =
                RuleValidator.validate(RoutingSettings(rules = preset.rules), known, known)
            assertTrue("$preset: $problems", problems.isEmpty())
        }
    }

    @Test
    fun `v2rayNG rules round trip through the codec`() {
        val input =
            """
            [
              {"remarks":"Block udp443","outboundTag":"block","port":"443","network":"udp"},
              {"remarks":"Direct RU","outboundTag":"direct","domain":["geosite:category-ru"]},
              {"remarks":"Proxy","outboundTag":"proxy","ip":["geoip:telegram"],"enabled":false},
              {"remarks":"Other","outboundTag":"my-balancer","domain":["x.com"]},
              {"inboundTag":["socks"],"outboundTag":"direct","port":"53"}
            ]
            """
        val result = RuleCodec.import(input)
        assertEquals(3, result.rules.size)
        assertEquals(2, result.skipped)
        assertEquals(RouteTarget.BLOCK, result.rules[0].target)
        assertFalse(result.rules[2].enabled)
        val again = RuleCodec.import(RuleCodec.export(result.rules)).rules
        assertEquals(result.rules.map { it.copy(id = "") }, again.map { it.copy(id = "") })
    }

    @Test
    fun `xray config rules are imported`() {
        val input =
            """{"routing":{"rules":[{"type":"field","outboundTag":"direct","ip":["geoip:private"]}]}}"""
        val result = RuleCodec.import(input)
        assertEquals(listOf("geoip:private"), result.rules.single().ips)
    }

    @Test
    fun `settings json tolerates unknown fields and keeps defaults`() {
        val json = """{"enabled":true,"futureField":42,"defaultTarget":"direct"}"""
        val settings =
            RoutingSettingsStore.JSON.decodeFromString(RoutingSettings.serializer(), json)
        assertTrue(settings.enabled)
        assertEquals(RouteTarget.DIRECT, settings.defaultTarget)
        assertEquals(RoutingSettings().rules, settings.rules)
    }

    @Test
    fun `geo catalog reads category codes`() {
        val bytes = geoList("YOUTUBE", "CATEGORY-RU")
        assertEquals(
            listOf("YOUTUBE", "CATEGORY-RU"),
            GeoCatalog.readCodes(ByteArrayInputStream(bytes)),
        )
    }

    /** Builds a minimal GeoSiteList: entry{ country_code, domain{ value } }. */
    private fun geoList(vararg codes: String): ByteArray {
        val out = ByteArrayOutputStream()
        codes.forEach { code ->
            val entry = ByteArrayOutputStream()
            entry.write(0x0A)
            entry.write(code.length)
            entry.write(code.toByteArray())
            val domain = byteArrayOf(0x12, 0x05, 0x08, 0x02, 0x12, 0x01, 'a'.code.toByte())
            entry.write(domain)
            out.write(0x0A)
            out.write(entry.size())
            out.write(entry.toByteArray())
        }
        return out.toByteArray()
    }
}
