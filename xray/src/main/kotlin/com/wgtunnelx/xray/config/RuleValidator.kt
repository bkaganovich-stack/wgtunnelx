package com.wgtunnelx.xray.config

import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings

/** A problem found before Xray is started, so the user sees it instead of a cryptic core error. */
sealed interface RuleProblem {
    val ruleName: String

    data class MissingGeoFile(override val ruleName: String, val fileName: String) : RuleProblem

    data class UnknownCategory(
        override val ruleName: String,
        val fileName: String,
        val category: String,
    ) : RuleProblem

    data class InvalidValue(override val ruleName: String, val field: String, val value: String) :
        RuleProblem
}

/**
 * Static checks for rules. Geo categories are checked against the codes actually present in the
 * downloaded files ([geoSiteCodes]/[geoIpCodes], lower case; null when the file is absent).
 */
object RuleValidator {

    private val PROTOCOLS = setOf("http", "tls", "quic", "bittorrent")
    private val NETWORKS = setOf("tcp", "udp", "tcp,udp", "udp,tcp")
    private val PORT_LIST = Regex("""^\d{1,5}(-\d{1,5})?(,\d{1,5}(-\d{1,5})?)*$""")
    private val IPV4_CIDR = Regex("""^(\d{1,3})(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val IPV6_CIDR = Regex("""^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*(/\d{1,3})?$""")

    fun validate(
        settings: RoutingSettings,
        geoSiteCodes: Set<String>?,
        geoIpCodes: Set<String>?,
    ): List<RuleProblem> {
        if (settings.useCustomConfig) return emptyList()
        return settings.rules
            .filter { it.enabled }
            .flatMap { validateRule(it, geoSiteCodes, geoIpCodes) }
    }

    fun validateRule(
        rule: RoutingRule,
        geoSiteCodes: Set<String>?,
        geoIpCodes: Set<String>?,
    ): List<RuleProblem> {
        val name = rule.name.ifBlank { rule.id }
        val problems = mutableListOf<RuleProblem>()

        rule.domains.forEach { raw ->
            val value = raw.trim()
            when {
                value.startsWith("geosite:", ignoreCase = true) -> {
                    val code = geoCode(value.substringAfter(':'))
                    when {
                        geoSiteCodes == null ->
                            problems += RuleProblem.MissingGeoFile(name, "geosite.dat")
                        code !in geoSiteCodes ->
                            problems += RuleProblem.UnknownCategory(name, "geosite.dat", code)
                    }
                }
                value.startsWith("regexp:") ->
                    if (runCatching { Regex(value.removePrefix("regexp:")) }.isFailure) {
                        problems += RuleProblem.InvalidValue(name, "domain", value)
                    }
                value.isEmpty() || value.any { it.isWhitespace() } ->
                    problems += RuleProblem.InvalidValue(name, "domain", value)
            }
        }

        rule.ips.forEach { raw ->
            val value = raw.trim()
            when {
                value.startsWith("geoip:", ignoreCase = true) -> {
                    val code = geoCode(value.substringAfter(':').removePrefix("!"))
                    when {
                        geoIpCodes == null ->
                            problems += RuleProblem.MissingGeoFile(name, "geoip.dat")
                        code !in geoIpCodes ->
                            problems += RuleProblem.UnknownCategory(name, "geoip.dat", code)
                    }
                }
                value.startsWith("ext:") -> Unit
                !isIpOrCidr(value) -> problems += RuleProblem.InvalidValue(name, "ip", value)
            }
        }

        val ports = rule.ports.replace(" ", "")
        if (ports.isNotEmpty() && (!PORT_LIST.matches(ports) || !portsInRange(ports))) {
            problems += RuleProblem.InvalidValue(name, "port", rule.ports)
        }
        val network = rule.network.replace(" ", "").lowercase()
        if (network.isNotEmpty() && network !in NETWORKS) {
            problems += RuleProblem.InvalidValue(name, "network", rule.network)
        }
        rule.protocols
            .filter { it.trim().lowercase() !in PROTOCOLS }
            .forEach { problems += RuleProblem.InvalidValue(name, "protocol", it) }
        return problems
    }

    /** `category-ads-all@ads` -> `category-ads-all` */
    private fun geoCode(value: String): String = value.substringBefore('@').trim().lowercase()

    fun isIpOrCidr(value: String): Boolean {
        if (IPV4_CIDR.matches(value)) {
            val (address, prefix) = value.split('/').let { it[0] to it.getOrNull(1) }
            val octetsOk = address.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }
            val prefixOk = prefix == null || (prefix.toIntOrNull() ?: 99) in 0..32
            return octetsOk && prefixOk
        }
        if (IPV6_CIDR.matches(value)) {
            val prefix = value.substringAfter('/', "")
            return prefix.isEmpty() || (prefix.toIntOrNull() ?: 999) in 0..128
        }
        return false
    }

    private fun portsInRange(ports: String): Boolean =
        ports.split(',', '-').all { (it.toIntOrNull() ?: 0) in 1..65535 }
}
