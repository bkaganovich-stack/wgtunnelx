package com.zaneschepke.wireguardautotunnel.xray

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zaneschepke.networkmonitor.ConnectivityState
import com.zaneschepke.networkmonitor.StableNetworkEngine
import com.zaneschepke.networkmonitor.VpnState
import com.zaneschepke.wireguardautotunnel.core.orchestration.AutoTunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.repository.AutoTunnelSettingsRepository
import com.zaneschepke.wireguardautotunnel.service.autotunnel.AutoTunnelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * WG Tunnel X: keeps auto-tunnel alive and visible in release logs.
 * - Auto-tunnel is a foreground service. If the process is stopped (force stop, some OEM task
 *   killers, a crash), Android does not bring it back, while the setting still says "on". When the
 *   app comes to the foreground in that state, the service is started again.
 * - Release builds drop debug logs, so the auto-tunnel decisions left no trace. The service state,
 *   the network it sees and every tunnel start/stop with its source are logged at INFO.
 */
class AutoTunnelWatchdog(
    private val stateHolder: AutoTunnelStateHolder,
    private val settings: AutoTunnelSettingsRepository,
    private val coordinator: AutoTunnelCoordinator,
    private val networkEngine: () -> StableNetworkEngine,
    private val tunnelCoordinator: () -> TunnelCoordinator,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    fun start() {
        scope.launch {
            stateHolder.active.collect { active ->
                Timber.i("Auto-tunnel service %s", if (active) "running" else "not running")
            }
        }
        scope.launch {
            stateHolder.active.collectLatest { active ->
                if (!active) return@collectLatest
                // resolved only while auto-tunnel runs: creating it starts network monitoring
                networkEngine()
                    .stableState
                    .map { it?.state?.describe() }
                    .distinctUntilChanged()
                    .collect { Timber.i("Auto-tunnel network: %s", it ?: "unknown") }
            }
        }
        scope.launch {
            tunnelCoordinator().actions.collect { event ->
                val action = if (event is TunnelActionEvent.Started) "started" else "stopped"
                Timber.i("Tunnel %d %s by %s", event.tunnelId, action, event.source)
            }
        }
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { resumeIfStopped() }
    }

    private suspend fun resumeIfStopped() {
        if (stateHolder.active.value) return
        if (!settings.getAutoTunnelSettings().isAutoTunnelEnabled) return
        Timber.w("Auto-tunnel is enabled but its service was not running, starting it")
        runCatching { coordinator.start() }
            .onFailure { Timber.e(it, "Cannot start the auto-tunnel service") }
    }

    private fun ConnectivityState.describe(): String {
        val vpn =
            when (val v = vpnState) {
                VpnState.Inactive -> "off"
                is VpnState.Active -> if (v.hasInternet) "on" else "on (no validated internet)"
            }
        return "$this, usable=${hasUsableNetwork()}, vpn=$vpn"
    }
}
