package com.wgtunnelx.xray

import com.wgtunnelx.xray.config.SessionParams
import com.wgtunnelx.xray.config.StrictSplit
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingPresets
import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictSplitTest {

    private val params =
        SessionParams(
            socksPort = 40000,
            socksUser = "u",
            socksPass = "p",
            tunnelDns = listOf("10.2.0.1"),
            tunnelNetworks = listOf("10.2.0.0/24"),
            packageUids = mapOf("org.telegram.messenger" to 10123, "org.example" to 10200),
        )

    private val settings =
        RoutingSettings(
            enabled = true,
            bypassLan = true,
            rules =
                RoutingPresets.RUSSIA_DIRECT.rules +
                    RoutingRule(
                        name = "apps",
                        packages = listOf("org.telegram.messenger", "org.example"),
                        target = RouteTarget.TUNNEL,
                    ),
        )

    private fun rules(strict: StrictSplit?, s: RoutingSettings = settings): List<JsonObject> =
        Json.parseToJsonElement(XrayConfigBuilder.build(s, params.copy(strictSplit = strict)))
            .jsonObject["routing"]!!
            .jsonObject["rules"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.outbound() = this["outboundTag"]!!.jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String>? =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }

    private fun JsonObject.isStrict() = this["ruleTag"]?.jsonPrimitive?.content == "strict split"

    @Test
    fun `off leaves the rules unchanged`() {
        assertTrue(rules(null).none { it.isStrict() })
    }

    @Test
    fun `exclude blocks excluded and unidentified owners before every other rule`() {
        val strict = StrictSplit(StrictSplit.Mode.EXCLUDE, setOf(10300, 10050))
        val rules = rules(strict)
        val first = rules.first()
        assertEquals("block", first.outbound())
        assertEquals(listOf("tun"), first.strings("inboundTag"))
        assertEquals(listOf("10050", "10300", "-1", "0"), first.strings("process"))
        // the DNS hijack, the tunnel networks and everything else follow unchanged
        assertEquals(rules(null), rules.drop(1))
    }

    @Test
    fun `exclude still blocks unidentified owners when no excluded app is installed`() {
        val first = rules(StrictSplit(StrictSplit.Mode.EXCLUDE, emptySet())).first()
        assertTrue(first.isStrict())
        assertEquals(listOf("-1", "0"), first.strings("process"))
    }

    @Test
    fun `include narrows every passing rule to the included apps and blocks the rest`() {
        val strict = StrictSplit(StrictSplit.Mode.INCLUDE, setOf(10123, 10400))
        val rules = rules(strict)
        val last = rules.last()
        assertTrue(last.isStrict())
        assertEquals("block", last.outbound())
        assertNull(last["process"])
        assertEquals("tcp,udp", last["network"]!!.jsonPrimitive.content)

        rules.dropLast(1).forEach { r ->
            val internalDns =
                r.strings("inboundTag")?.all { it == "dns-remote" || it == "dns-direct" } == true
            when {
                internalDns -> assertNull(r["process"])
                r.outbound() == "block" -> Unit // blocking is fine for anyone
                r["ruleTag"]?.jsonPrimitive?.content == "apps" ->
                    // org.example is installed but not included, so only Telegram stays
                    assertEquals(listOf("10123"), r.strings("process"))
                else -> {
                    // app rules of the preset keep their included apps, all others get every one
                    val owners = r.strings("process")!!
                    assertTrue(owners.isNotEmpty() && owners.all { it in setOf("10123", "10400") })
                }
            }
        }
        // the default target catch-all is narrowed to every included app
        assertEquals(listOf("10123", "10400"), rules[rules.size - 2].strings("process"))
        // the DNS hijack comes first and is narrowed as well
        assertEquals("dns-out", rules.first().outbound())
        assertEquals(listOf("10123", "10400"), rules.first().strings("process"))
    }

    @Test
    fun `include drops an app rule whose apps are all outside the tunnel`() {
        val strict = StrictSplit(StrictSplit.Mode.INCLUDE, setOf(10400))
        assertTrue(rules(strict).none { it["ruleTag"]?.jsonPrimitive?.content == "apps" })
    }

    @Test
    fun `include with no installed app lets nothing through`() {
        val rules = rules(StrictSplit(StrictSplit.Mode.INCLUDE, emptySet()))
        assertTrue(
            rules.all {
                it.outbound() == "block" ||
                    it.strings("inboundTag")?.any { t -> t.startsWith("dns-") } == true
            },
        )
        assertTrue(rules.last().isStrict())
    }

    @Test
    fun `custom config is not changed`() {
        val custom = RoutingSettings(useCustomConfig = true, customConfig = "{\"routing\":{}}")
        val strict = StrictSplit(StrictSplit.Mode.EXCLUDE, setOf(10300))
        assertEquals(
            "{\"routing\":{}}",
            XrayConfigBuilder.build(custom, params.copy(strictSplit = strict)),
        )
    }
}
