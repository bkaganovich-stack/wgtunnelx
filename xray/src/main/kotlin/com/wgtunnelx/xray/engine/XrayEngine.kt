package com.wgtunnelx.xray.engine

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.wgtunnelx.xray.XrayProcess
import com.wgtunnelx.xray.engine.XrayIpc.toBundle
import com.wgtunnelx.xray.engine.XrayIpc.toEngineState
import com.wgtunnelx.xray.engine.XrayIpc.toStart
import com.wgtunnelx.xray.engine.XrayIpc.toTraffic
import com.wgtunnelx.xray.geo.GeoDataManager
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * App-side owner of the Xray routing session. The app talks to this class; it drives
 * [XrayVpnService] in the `:xray` process over a binding and mirrors its state. Only one routed
 * tunnel can run at a time (Android allows one VPN).
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

    /**
     * Called when routing ended without the app asking: the system revoked the VPN or the Xray
     * process died. The WireGuard side should be stopped too, so nothing looks connected.
     */
    @Volatile var revokeHandler: ((tunnelId: Int?) -> Unit)? = null

    private val replies = Messenger(Handler(Looper.getMainLooper(), ::onReply))

    // binding state, touched from the main thread (callbacks) and callers (under the lock)
    private val lock = Any()
    private var connection: Connection? = null

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

    /** Brings up the TUN and Xray; suspends until the Xray process reports success or failure. */
    suspend fun start(request: RoutingRequest): Result<Unit> {
        _traffic.value = emptyMap()
        _state.value = EngineState.Starting(request.tunnelId)
        val service =
            runCatching { withTimeoutOrNull(BIND_TIMEOUT) { bind().await() } }.getOrNull()
                ?: return fail(request.tunnelId, "The routing service did not start")
        val start = request.toStart(geo.assetDir.absolutePath)
        try {
            service.send(
                Message.obtain(null, XrayIpc.MSG_START).also {
                    it.data = start.toBundle()
                    it.replyTo = replies
                }
            )
        } catch (e: RemoteException) {
            return fail(request.tunnelId, "The routing service is gone")
        }
        val result =
            withTimeoutOrNull(START_TIMEOUT) { state.first { it !is EngineState.Starting } }
                ?: EngineState.Failed(request.tunnelId, "Timed out starting Xray")
        return when (result) {
            is EngineState.Running -> Result.success(Unit)
            is EngineState.Failed -> {
                Timber.tag(XrayCore.TAG).e("Routing failed to start: %s", result.message)
                stop()
                _state.value = result
                Result.failure(IllegalStateException(result.message))
            }
            else -> Result.failure(IllegalStateException("Routing stopped while starting"))
        }
    }

    private suspend fun fail(tunnelId: Int, message: String): Result<Unit> {
        Timber.tag(XrayCore.TAG).e(message)
        stop()
        _state.value = EngineState.Failed(tunnelId, message)
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Stops Xray and closes the VPN. If the Xray process does not confirm in time it is killed, which
     * closes the TUN as well: a stop always takes effect.
     */
    suspend fun stop() {
        val conn = synchronized(lock) { connection }
        if (conn != null) {
            val service = conn.messenger
            val confirmed =
                service != null &&
                    runCatching {
                            service.send(
                                Message.obtain(null, XrayIpc.MSG_STOP).also { it.replyTo = replies }
                            )
                        }
                        .isSuccess &&
                    withTimeoutOrNull(STOP_TIMEOUT) { conn.stopped.await() } != null
            // forget the binding first, so its death below is not taken for a crash
            synchronized(lock) { if (connection === conn) connection = null }
            if (!confirmed) {
                Timber.tag(XrayCore.TAG).w("Xray did not confirm the stop, ending its process")
                killXrayProcess()
            }
            runCatching { appContext.unbindService(conn) }
        }
        if (_state.value !is EngineState.Failed) _state.value = EngineState.Stopped
    }

    /** Asks the Xray process for fresh counters; they are added to [traffic] when they arrive. */
    fun refreshTraffic() {
        if (_state.value !is EngineState.Running) return
        val service = synchronized(lock) { connection }?.messenger ?: return
        runCatching {
            service.send(Message.obtain(null, XrayIpc.MSG_TRAFFIC).also { it.replyTo = replies })
        }
    }

    private fun onReply(msg: Message): Boolean {
        when (msg.what) {
            XrayIpc.MSG_STATE -> onRemoteState(msg.data.toEngineState())
            XrayIpc.MSG_TRAFFIC_RESULT -> addTraffic(msg.data.toTraffic())
            else -> return false
        }
        return true
    }

    private fun onRemoteState(state: EngineState) {
        Timber.tag(XrayCore.TAG).i("engine state -> %s", state)
        when (state) {
            EngineState.Stopped -> synchronized(lock) { connection }?.stopped?.complete(Unit)
            is EngineState.Revoked -> {
                _state.value = state
                revokeHandler?.invoke(state.tunnelId)
                return
            }
            else -> Unit
        }
        // a late report from a previous session must not override a newer start or stop
        val current = _state.value
        val relevant =
            when (state) {
                EngineState.Stopped -> false
                is EngineState.Running -> current is EngineState.Starting
                is EngineState.Failed -> current is EngineState.Starting || current is EngineState.Running
                else -> false
            }
        if (relevant) _state.value = state
    }

    private fun addTraffic(delta: Map<String, OutboundTraffic>) {
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

    private fun bind(): CompletableDeferred<Messenger> =
        synchronized(lock) {
            connection?.let {
                return it.service
            }
            val conn = Connection()
            val intent =
                Intent(appContext, XrayVpnService::class.java).setAction(XrayIpc.ACTION_CONTROL)
            val bound =
                runCatching {
                        appContext.bindService(
                            intent,
                            conn,
                            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                        )
                    }
                    .onFailure { Timber.tag(XrayCore.TAG).e(it, "Cannot bind the routing service") }
                    .getOrDefault(false)
            if (!bound) {
                runCatching { appContext.unbindService(conn) }
                return CompletableDeferred<Messenger>().also {
                    it.completeExceptionally(IllegalStateException("bind failed"))
                }
            }
            connection = conn
            conn.service
        }

    private fun killXrayProcess() {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return
        val name = appContext.packageName + XrayProcess.SUFFIX
        am.runningAppProcesses
            ?.filter { it.processName == name }
            ?.forEach {
                Timber.tag(XrayCore.TAG).w("Killing %s (%d)", name, it.pid)
                Process.killProcess(it.pid)
            }
    }

    /** Called from the main thread when the Xray process went away on its own. */
    private fun onXrayDied(conn: Connection) {
        val current =
            synchronized(lock) { (connection === conn).also { if (it) connection = null } }
        if (!current) return
        runCatching { appContext.unbindService(conn) }
        val state = _state.value
        if (state !is EngineState.Running && state !is EngineState.Starting) return
        val id = activeTunnelId
        Timber.tag(XrayCore.TAG).e("The Xray process died while routing tunnel %s", id)
        _state.value = EngineState.Failed(id, "The routing process stopped unexpectedly")
        if (state is EngineState.Running) revokeHandler?.invoke(id)
    }

    private inner class Connection : ServiceConnection {
        val service = CompletableDeferred<Messenger>()
        val stopped = CompletableDeferred<Unit>()
        @Volatile var messenger: Messenger? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val m = Messenger(binder)
            messenger = m
            service.complete(m)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stopped.complete(Unit)
            onXrayDied(this)
        }

        override fun onBindingDied(name: ComponentName?) {
            stopped.complete(Unit)
            onXrayDied(this)
        }

        override fun onNullBinding(name: ComponentName?) {
            service.completeExceptionally(IllegalStateException("routing service refused binding"))
        }
    }

    internal fun onAlwaysOn() {
        alwaysOnHandler?.invoke() ?: Timber.w("Always-on trigger without handler")
    }

    companion object {
        private val BIND_TIMEOUT = 10.seconds
        private val START_TIMEOUT = 20.seconds
        private val STOP_TIMEOUT = 5.seconds

        @Volatile
        internal var instance: XrayEngine? = null
            private set
    }
}
