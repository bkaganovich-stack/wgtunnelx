package com.zaneschepke.wireguardautotunnel.xray

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.model.ProxyConfig
import com.wgtunnel.backend.model.dns.TunnelDnsConfig
import com.wgtunnel.backend.service.RuntimeManager
import com.wgtunnel.parser.Config
import com.wgtunnelx.xray.XrayRouting
import com.wgtunnelx.xray.config.RuleProblem
import com.wgtunnelx.xray.config.RuleValidator
import com.wgtunnelx.xray.config.SessionParams
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.engine.EngineState
import com.wgtunnelx.xray.engine.RoutingRequest
import com.wgtunnelx.xray.model.RoutingSettings
import com.zaneschepke.wireguardautotunnel.core.tunnel.AppProvider
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * WG Tunnel X: split routing on top of the upstream backend.
 *
 * When routing applies to a tunnel that is about to start in VPN mode, the tunnel is started in the
 * upstream userspace proxy mode instead (AmneziaWG + netstack, SOCKS5 on 127.0.0.1 with random
 * credentials) and [com.wgtunnelx.xray.engine.XrayVpnService] owns the VPN: Xray reads the TUN,
 * sniffs domains and sends each connection to the tunnel, direct or nowhere according to the rules.
 * Every other start is passed through unchanged, so with routing off the app behaves exactly like
 * upstream WG Tunnel.
 */
class RoutingTunnelProvider(
    private val delegate: TunnelProvider,
    private val routing: XrayRouting,
    private val appProvider: AppProvider,
    private val context: Context,
    private val scope: CoroutineScope,
) : TunnelProvider by delegate {

    private val mutex = Mutex()
    @Volatile private var routedTunnelId: Int? = null

    init {
        routing.engine.alwaysOnHandler = { RuntimeManager.alwaysOnCallback?.alwaysOnTriggered() }
        routing.engine.revokeHandler = { id ->
            scope.launch {
                Timber.tag(TAG).w("Routing ended on its own (revoke or crash), stopping tunnel %s", id)
                if (id != null) stopTunnel(id) else stopActiveTunnels()
            }
        }
        // the backend can stop a tunnel on its own (errors, revoke of a kill switch, ...)
        scope.launch {
            delegate.backendStatus
                .distinctUntilChangedBy { it.activeTunnels.keys }
                .collect { status ->
                    val routed = routedTunnelId ?: return@collect
                    if (routed !in status.activeTunnels.keys && routing.engine.isActive) {
                        mutex.withLock {
                            if (routedTunnelId == routed) {
                                Timber.tag(TAG).i("Tunnel %d went down, stopping routing", routed)
                                routing.engine.stop()
                                routedTunnelId = null
                            }
                        }
                    }
                }
        }
    }

    override suspend fun startTunnel(
        tunnel: Tunnel,
        mode: BackendMode,
        tunnelDnsConfig: TunnelDnsConfig?,
        outerConfig: Config?,
    ): Result<Unit> = mutex.withLock {
        val settings = routing.store.current
        val routed = mode is BackendMode.Vpn && settings.appliesTo(tunnel.id)
        Timber.tag(TAG).i("Starting tunnel %d (%s), routing=%s", tunnel.id, mode::class.simpleName, routed)
        if (mode !is BackendMode.Vpn || !routed) {
            stopRoutingLocked()
            return@withLock delegate.startTunnel(tunnel, mode, tunnelDnsConfig, outerConfig)
        }
        startRoutedLocked(tunnel, mode.config, settings, tunnelDnsConfig, outerConfig)
    }

    private suspend fun startRoutedLocked(
        tunnel: Tunnel,
        config: Config,
        settings: RoutingSettings,
        tunnelDnsConfig: TunnelDnsConfig?,
        outerConfig: Config?,
    ): Result<Unit> {
        routing.geo.refreshState()
        val geo = routing.geo.state.value
        val problems = RuleValidator.validate(settings, geo.geoSite?.codes, geo.geoIp?.codes)
        if (problems.isNotEmpty()) {
            return Result.failure(RoutingConfigException(problems))
        }

        stopRoutingLocked()
        val port = freeLocalPort()
        val user = "x" + randomToken(6)
        val pass = randomToken(24)
        val proxyMode =
            BackendMode.Proxy.Standard(
                config = config,
                proxyConfig =
                    ProxyConfig(
                        socks5 =
                            ProxyConfig.Socks5(
                                host = SessionParams.LOCALHOST,
                                port = port,
                                username = user,
                                password = pass,
                            )
                    ),
            )
        delegate.startTunnel(tunnel, proxyMode, tunnelDnsConfig, outerConfig).onFailure {
            return Result.failure(it)
        }

        val params = sessionParams(config, port, user, pass, settings)
        val json = runCatching {
            XrayConfigBuilder.build(settings, params)
        }
            .getOrElse {
                delegate.stopTunnel(tunnel.id)
                return Result.failure(it)
            }
        val request =
            RoutingRequest(
                tunnelId = tunnel.id,
                sessionName = tunnel.name,
                params = params,
                configJson = json,
                includedApps = config.`interface`.allIncludedApps,
                excludedApps = config.`interface`.allExcludedApps,
                metered = tunnel.isMetered,
                configureIntent = appProvider.createVpnConfigurePendingIntent(context),
            )
        return routing.engine
            .start(request)
            .onSuccess { routedTunnelId = tunnel.id }
            .onFailure {
                Timber.tag(TAG).e(it, "Routing failed to start, stopping tunnel %d", tunnel.id)
                delegate.stopTunnel(tunnel.id)
            }
    }

    override suspend fun stopTunnel(tunnelId: Int): Result<Unit> = mutex.withLock {
        Timber.tag(TAG).i("Stopping tunnel %d", tunnelId)
        if (routedTunnelId == tunnelId) stopRoutingLocked()
        delegate.stopTunnel(tunnelId)
    }

    override suspend fun stopActiveTunnels(): Result<Unit> = mutex.withLock {
        Timber.tag(TAG).i("Stopping all tunnels")
        stopRoutingLocked()
        delegate.stopActiveTunnels()
    }

    private suspend fun stopRoutingLocked() {
        if (routing.engine.isActive || routedTunnelId != null) routing.engine.stop()
        routedTunnelId = null
    }

    val isRouting: Boolean
        get() = routing.engine.state.value is EngineState.Running

    private fun sessionParams(
        config: Config,
        port: Int,
        user: String,
        pass: String,
        settings: RoutingSettings,
    ): SessionParams {
        val iface = config.`interface`
        val dns =
            iface.dns
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && isIpLiteral(it) }
                .orEmpty()
        val addresses = iface.address?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val allowed =
            config.peers
                .flatMap { it.allowedIPs?.split(',').orEmpty() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        // private AllowedIPs (corporate LANs, the tunnel's own DNS) must win over the LAN bypass
        val tunnelNetworks =
            (allowed.filter(::isPrivateNetwork) + addresses.orEmpty() + dns.map { hostRoute(it) })
                .distinct()
        val hasIpv6 = iface.hasIpv6Address && allowed.any { it.contains(':') }
        if (!hasIpv6) Timber.tag(TAG).i("Tunnel has no IPv6, IPv6 connections are refused")
        val packages = settings.rules.filter { it.enabled }.flatMap { it.packages }.toSet()
        return SessionParams(
            socksPort = port,
            socksUser = user,
            socksPass = pass,
            tunnelDns = dns,
            tunnelNetworks = tunnelNetworks,
            tunnelHasIpv6 = hasIpv6,
            packageUids = resolveUids(packages),
        )
    }

    private fun resolveUids(packages: Set<String>): Map<String, Int> {
        if (packages.isEmpty()) return emptyMap()
        // the owner of a connection can only be looked up from Android 10 on; without a UID the
        // rule is dropped instead of being checked, and failing, for every connection
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.tag(TAG).w("App rules need Android 10 or newer, skipping them")
            return emptyMap()
        }
        val pm = context.packageManager
        val missing = mutableListOf<String>()
        val uids =
            packages
                .mapNotNull { pkg ->
                    try {
                        pkg to pm.getApplicationInfo(pkg, 0).uid
                    } catch (e: PackageManager.NameNotFoundException) {
                        missing += pkg
                        null
                    }
                }
                .toMap()
        Timber.tag(TAG).i("App rules: %s; not installed: %s", uids, missing)
        return uids
    }

    private fun isIpLiteral(value: String): Boolean =
        RuleValidator.isIpOrCidr(value) && !value.contains('/')

    private fun isPrivateNetwork(cidr: String): Boolean {
        val address = cidr.substringBefore('/')
        val prefix = cidr.substringAfter('/', "").toIntOrNull()
        if (!isIpLiteral(address)) return false
        val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        val bytes = ip.address
        val cgnat = bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 64
        val ula = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
        val minPrefix = if (bytes.size == 4) 8 else 7
        return (ip.isSiteLocalAddress || cgnat || ula) && (prefix == null || prefix >= minPrefix)
    }

    private fun hostRoute(ip: String) = if (ip.contains(':')) "$ip/128" else "$ip/32"

    private fun freeLocalPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(SessionParams.LOCALHOST)).use { it.localPort }

    private fun randomToken(length: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}

// fixed tag: R8 renames the class, and the log must stay searchable
private const val TAG = "WgxRouting"

/** Raised before anything is started when the rules cannot work with the installed geo files. */
class RoutingConfigException(val problems: List<RuleProblem>) :
    IllegalStateException(problems.joinToString("; ") { it.describe() })

fun RuleProblem.describe(): String =
    when (this) {
        is RuleProblem.MissingGeoFile -> "$ruleName: $fileName is not downloaded"
        is RuleProblem.UnknownCategory -> "$ruleName: '$category' not found in $fileName"
        is RuleProblem.InvalidValue -> "$ruleName: invalid $field '$value'"
    }
