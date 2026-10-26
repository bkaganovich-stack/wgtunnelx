package com.zaneschepke.wireguardautotunnel.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.wgtunnel.backend.Backend
import com.wgtunnelx.xray.engine.EngineState
import com.wgtunnelx.xray.engine.XrayEngine
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * WG Tunnel X: brings a routed tunnel back after the phone moves to another network.
 *
 * With routing on, the tunnel runs in WG Tunnel's proxy (userspace) mode. After the underlying
 * network changes, its WireGuard socket can keep sending from the address of the network that is
 * gone, and the handshake fails until the tunnel is restarted. Upstream recovery does restart it,
 * but holds the restart while the screen is off, so a phone in a pocket stayed without the tunnel
 * for minutes (26.10: 4 minutes after a mobile network change). Telegram and notifications sat in
 * "Connecting" all that time.
 *
 * Here the tunnel is restarted once per network, a few seconds after it stops answering on a
 * network it has not worked on yet. A restart only re-creates the WireGuard device; the local
 * proxy keeps its port and credentials, so Xray carries on.
 */
class RoutedTunnelHealer(
    context: Context,
    private val backend: () -> Backend,
    private val engine: XrayEngine,
    private val scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** The default network of this app, which is excluded from its own VPN: the real one. */
    private val network = MutableStateFlow<Network?>(null)

    fun start() {
        val cm = connectivity ?: return
        runCatching {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(n: Network) {
                            network.value = n
                        }

                        override fun onLost(n: Network) {
                            if (network.value == n) network.value = null
                        }
                    }
                )
            }
            .onFailure {
                Timber.tag(TAG).e(it, "Cannot watch the network")
                return
            }
        scope.launch {
            engine.state
                .map { (it as? EngineState.Running)?.tunnelId }
                .distinctUntilChanged()
                .collectLatest { id -> if (id != null) watch(id) }
        }
    }

    private suspend fun watch(tunnelId: Int) {
        val backend = backend()
        // The network the tunnel became healthy on. Not updated when the network changes while
        // the tunnel still counts as healthy: that state is stale until the next handshake fails.
        var workedOn: Network? = null
        var wasHealthy: Boolean? = null
        var restartedFor: Network? = null
        val healthy =
            backend.status
                .map { it.activeTunnels[tunnelId]?.isTransportHealthy() }
                .distinctUntilChanged()
        combine(healthy, network) { h, n -> h to n }
            .collectLatest { (isHealthy, net) ->
                if (isHealthy == true && wasHealthy != true) workedOn = net
                wasHealthy = isHealthy
                if (isHealthy != false || net == null) return@collectLatest
                if (net == workedOn || net == restartedFor) return@collectLatest
                // a change of health or network cancels the wait
                delay(SETTLE)
                restartedFor = net
                Timber.tag(TAG)
                    .i("Tunnel %d is not answering on a new network, restarting it", tunnelId)
                runCatching { backend.bounceTunnelDevice(tunnelId, false) }
                    .onSuccess { Timber.tag(TAG).i("Tunnel %d restart done: %s", tunnelId, it) }
                    .onFailure { Timber.tag(TAG).e(it, "Tunnel %d restart failed", tunnelId) }
            }
    }

    private companion object {
        const val TAG = "WgxRouting"

        /** Same settle time upstream recovery uses after a network change. */
        val SETTLE = 5.seconds
    }
}
