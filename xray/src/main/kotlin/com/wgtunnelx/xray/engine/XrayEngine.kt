package com.wgtunnelx.xray.engine

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.wgtunnelx.xray.geo.GeoDataManager
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Process-wide owner of the Xray routing session. The app talks to this class; [XrayVpnService]
 * reports back into it. Only one routed tunnel can run at a time (Android allows one VPN).
 */
class XrayEngine(context: Context, val geo: GeoDataManager) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow<Map<String, OutboundTraffic>>(emptyMap())
    /** Accumulated bytes per outbound tag (proxy/direct/block/dns-out) for the current session. */
    val traffic: StateFlow<Map<String, OutboundTraffic>> = _traffic.asStateFlow()

    /** Called when Android starts the VPN service for Always-on VPN. */
    @Volatile var alwaysOnHandler: (() -> Unit)? = null

    /** Called when the system revoked the VPN, so the WireGuard side can be stopped too. */
    @Volatile var revokeHandler: ((tunnelId: Int?) -> Unit)? = null

    @Volatile
    internal var pendingRequest: RoutingRequest? = null
        private set

    internal val core: XrayCore by lazy {
        XrayCore(
            geo.assetDir.absolutePath,
            appContext.getSystemService(ConnectivityManager::class.java),
        )
    }

    init {
        instance = this
    }

    val isActive: Boolean
        get() = _state.value is EngineState.Running || _state.value is EngineState.Starting

    val activeTunnelId: Int?
        get() =
            when (val s = _state.value) {
                is EngineState.Running -> s.tunnelId
                is EngineState.Starting -> s.tunnelId
                else -> null
            }

    /** Brings up the TUN and Xray; suspends until the service reports success or failure. */
    suspend fun start(request: RoutingRequest): Result<Unit> {
        pendingRequest = request
        _traffic.value = emptyMap()
        _state.value = EngineState.Starting(request.tunnelId)
        val intent =
            Intent(appContext, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START)
        try {
            // allowed from the background: the WG Tunnel proxy runtime is already a foreground
            // service at this point
            appContext.startService(intent)
        } catch (e: Exception) {
            Timber.e(e, "Cannot start the routing VPN service")
            _state.value = EngineState.Failed(request.tunnelId, e.message ?: e.toString())
            return Result.failure(e)
        }
        val result =
            withTimeoutOrNull(START_TIMEOUT) { state.first { it !is EngineState.Starting } }
                ?: EngineState.Failed(request.tunnelId, "Timed out starting Xray")
        return when (result) {
            is EngineState.Running -> Result.success(Unit)
            is EngineState.Failed -> {
                stop()
                Result.failure(IllegalStateException(result.message))
            }
            else -> Result.failure(IllegalStateException("Routing stopped while starting"))
        }
    }

    fun stop() {
        pendingRequest = null
        XrayVpnService.instance?.stopRouting(userInitiated = true)
        if (_state.value !is EngineState.Failed) _state.value = EngineState.Stopped
    }

    /** Pulls fresh counters from the core and adds them to the session totals. */
    fun refreshTraffic() {
        if (_state.value !is EngineState.Running) return
        val delta = core.drainTraffic()
        if (delta.isEmpty()) return
        _traffic.update { current ->
            val merged = current.toMutableMap()
            delta.forEach { (tag, t) ->
                val old = merged[tag]
                merged[tag] =
                    if (old == null) t
                    else OutboundTraffic(old.uplink + t.uplink, old.downlink + t.downlink)
            }
            merged
        }
    }

    internal fun report(state: EngineState) {
        Timber.tag(XrayCore.TAG).i("engine state -> %s", state)
        _state.value = state
        if (state is EngineState.Revoked) revokeHandler?.invoke(state.tunnelId)
    }

    internal fun onAlwaysOn() {
        alwaysOnHandler?.invoke() ?: Timber.w("Always-on trigger without handler")
    }

    companion object {
        private val START_TIMEOUT = 20.seconds

        @Volatile
        internal var instance: XrayEngine? = null
            private set
    }
}
