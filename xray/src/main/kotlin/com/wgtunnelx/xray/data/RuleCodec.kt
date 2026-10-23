package com.wgtunnelx.xray.data

import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Imports and exports rule lists in the formats people already have:
 * - v2rayNG "custom routing" (`[{"remarks","outboundTag","domain","ip","port","network",...}]`)
 * - Xray routing (`{"rules":[...]}`), a full Xray config (`{"routing":{"rules":[...]}}`) or a bare
 *   array of Xray rules.
 *
 * Rules pointing at outbound tags other than proxy/direct/block are skipped (and counted), since WG
 * Tunnel X only has those three exits.
 */
object RuleCodec {

    data class ImportResult(val rules: List<RoutingRule>, val skipped: Int)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun import(text: String): ImportResult {
        val root = json.parseToJsonElement(text.trim())
        val array: JsonArray =
            when (root) {
                is JsonArray -> root
                is JsonObject ->
                    (root["routing"] as? JsonObject)?.get("rules")?.jsonArray
                        ?: root["rules"]?.jsonArray
                        ?: throw IllegalArgumentException("No rules found")
                else -> throw IllegalArgumentException("Unsupported format")
            }
        var skipped = 0
        val rules = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null.also { skipped++ }
            val target = RouteTarget.fromTag(obj.string("outboundTag"))
            // DNS hijack rules of other clients are implementation details, not user rules
            if (target == null || obj.containsKey("inboundTag")) {
                skipped++
                return@mapNotNull null
            }
            val rule =
                RoutingRule(
                    name = obj.string("remarks") ?: obj.string("ruleTag") ?: "",
                    enabled = obj.boolean("enabled") ?: true,
                    target = target,
                    domains = obj.list("domain") + obj.list("domains"),
                    ips = obj.list("ip"),
                    ports = obj.string("port") ?: "",
                    network = obj.string("network") ?: "",
                    protocols = obj.list("protocol"),
                    packages = obj.list("packages"),
                )
            if (rule.isEmpty) {
                skipped++
                null
            } else rule
        }
        return ImportResult(rules, skipped)
    }

    /** Exports in the v2rayNG custom routing format, which is also readable by [import]. */
    fun export(rules: List<RoutingRule>): String {
        val array = buildJsonArray {
            rules.forEach { rule ->
                add(
                    buildJsonObject {
                        put("remarks", rule.name)
                        put("enabled", rule.enabled)
                        put("outboundTag", rule.target.tag)
                        if (rule.domains.isNotEmpty()) put("domain", strings(rule.domains))
                        if (rule.ips.isNotEmpty()) put("ip", strings(rule.ips))
                        if (rule.ports.isNotBlank()) put("port", rule.ports)
                        if (rule.network.isNotBlank()) put("network", rule.network)
                        if (rule.protocols.isNotEmpty()) put("protocol", strings(rule.protocols))
                        if (rule.packages.isNotEmpty()) put("packages", strings(rule.packages))
                    }
                )
            }
        }
        return json.encodeToString(JsonArray.serializer(), array)
    }

    /** Splits user input (lines, commas, spaces) into a clean list. */
    fun splitList(text: String): List<String> =
        text
            .split('\n', ',', ' ', '\t', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun JsonObject.string(key: String): String? =
        when (val v = this[key]) {
            is JsonPrimitive -> v.contentOrNull
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
            else -> null
        }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.list(key: String): List<String> =
        when (val v: JsonElement? = this[key]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            is JsonPrimitive -> v.contentOrNull?.let { splitList(it) } ?: emptyList()
            else -> emptyList()
        }.filter { it.isNotEmpty() }
}
