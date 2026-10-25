package com.wgtunnelx.xray.engine

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.wgtunnelx.xray.engine.XrayIpc.toBundle
import com.wgtunnelx.xray.engine.XrayIpc.toStart
import timber.log.Timber

/**
 * The VPN that carries routed traffic: a TUN whose packets are read by Xray-core directly (Xray
 * `tun` inbound, the fd is handed over with `xray.tun.fd`).
 *
 * Runs in the `:xray` process (see [com.wgtunnelx.xray.XrayProcess]) and is driven by [XrayEngine]
 * over a [Messenger] binding. If the app process dies, routing stops here too, so the VPN never
 * outlives the WireGuard side it sends traffic to.
 *
 * This app's own package is always excluded from the TUN. That keeps the WireGuard UDP socket of
 * the userspace tunnel and Xray's `direct` sockets on the underlying network without any socket
 * protection, and it makes routing loops impossible.
 */
class XrayVpnService : VpnService() {

    // every command runs here, in order; starting Xray loads the geo files and takes a moment
    private val thread = HandlerThread("xray-ctl").apply { start() }
    private val handler = Handler(thread.looper, ::handle)
    private val messenger = Messenger(handler)

    private var core: XrayCore? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var activeTunnelId: Int? = null
    private var client: Messenger? = null
    private var clientDeath: IBinder.DeathRecipient? = null

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == XrayIpc.ACTION_CONTROL) messenger.binder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SERVICE_INTERFACE) {
            // Always-on VPN: let the app start its default tunnel the regular way
            Timber.tag(TAG).i("Started by the system (Always-on VPN)")
            sendBroadcast(
                Intent(XrayAlwaysOnReceiver.ACTION).setClass(this, XrayAlwaysOnReceiver::class.java)
            )
            // the tunnel start that follows decides which VPN service owns the interface
            handler.post { if (tunFd == null) stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    private fun handle(msg: Message): Boolean {
        when (msg.what) {
            XrayIpc.MSG_START -> {
                msg.replyTo?.let(::attachClient)
                startRouting(msg.data.toStart())
            }
            XrayIpc.MSG_STOP -> {
                msg.replyTo?.let(::attachClient)
                stopRouting("app")
                report(EngineState.Stopped)
            }
            XrayIpc.MSG_TRAFFIC -> {
                val traffic = core?.takeIf { tunFd != null }?.drainTraffic().orEmpty()
                send(msg.replyTo, XrayIpc.MSG_TRAFFIC_RESULT, traffic.toBundle())
            }
            else -> return false
        }
        return true
    }

    private fun startRouting(start: XrayIpc.Start) {
        activeTunnelId = start.tunnelId
        try {
            if (prepare(this) != null) {
                throw IllegalStateException("VPN permission is not granted")
            }
            val fd = establish(start)
            tunFd?.close()
            tunFd = fd
            val core =
                core
                    ?: XrayCore(start.assetDir, getSystemService(ConnectivityManager::class.java))
                        .also { core = it }
            core.start(start.configJson, fd.fd)
            report(EngineState.Running(start.tunnelId, System.currentTimeMillis(), core.version))
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to start routing")
            teardown()
            report(EngineState.Failed(start.tunnelId, e.message ?: e.toString()))
        }
    }

    private fun establish(start: XrayIpc.Start): ParcelFileDescriptor {
        val builder =
            Builder()
                .setSession(start.sessionName)
                .setMtu(start.mtu)
                .addAddress(TUN_IPV4, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS_IPV4)
                // IPv6 is always captured so it cannot leak around the routing rules
                .addAddress(TUN_IPV6, 126)
                .addRoute("::", 0)
                .addDnsServer(TUN_DNS_IPV6)
        start.configureIntent?.let { builder.setConfigureIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(start.metered)
        builder.setUnderlyingNetworks(null)

        val self = packageName
        val included = start.includedApps.filter { it != self }
        if (included.isNotEmpty()) {
            included.forEach { pkg -> builder.allowApp(pkg) }
        } else {
            builder.addDisallowedApplication(self)
            start.excludedApps.filter { it != self }.forEach { pkg -> builder.disallowApp(pkg) }
        }
        return builder.establish() ?: throw IllegalStateException("VPN interface was not created")
    }

    private fun Builder.allowApp(pkg: String) {
        try {
            addAllowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.tag(TAG).w("Included app not installed: %s", pkg)
        }
    }

    private fun Builder.disallowApp(pkg: String) {
        try {
            addDisallowedApplication(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.tag(TAG).w("Excluded app not installed: %s", pkg)
        }
    }

    private fun stopRouting(reason: String) {
        Timber.tag(TAG).i("Stopping routing (%s)", reason)
        teardown()
        activeTunnelId = null
        stopSelf()
    }

    /** Stops Xray and closes the TUN. */
    private fun teardown() {
        core?.stop()
        runCatching { tunFd?.close() }.onFailure { Timber.tag(TAG).w(it, "close tun") }
        tunFd = null
    }

    private fun attachClient(messenger: Messenger) {
        if (client?.binder == messenger.binder) return
        detachClient()
        val recipient =
            IBinder.DeathRecipient {
                handler.post {
                    Timber.tag(TAG).w("App process died, stopping routing")
                    client = null
                    clientDeath = null
                    stopRouting("app died")
                }
            }
        try {
            messenger.binder.linkToDeath(recipient, 0)
        } catch (e: RemoteException) {
            // already gone: nothing can use the tunnel this start would bring up
            recipient.binderDied()
            return
        }
        client = messenger
        clientDeath = recipient
    }

    private fun detachClient() {
        val c = client ?: return
        clientDeath?.let { runCatching { c.binder.unlinkToDeath(it, 0) } }
        client = null
        clientDeath = null
    }

    private fun report(state: EngineState) {
        Timber.tag(XrayCore.TAG).i("engine state -> %s", state)
        send(client, XrayIpc.MSG_STATE, state.toBundle())
    }

    private fun send(target: Messenger?, what: Int, data: android.os.Bundle) {
        target ?: return
        try {
            target.send(Message.obtain(null, what).also { it.data = data })
        } catch (e: RemoteException) {
            Timber.tag(TAG).w("App is gone, cannot report %d", what)
        }
    }

    override fun onRevoke() {
        handler.post {
            Timber.tag(TAG).w("Routing VPN revoked")
            val id = activeTunnelId
            teardown()
            activeTunnelId = null
            report(EngineState.Revoked(id))
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        handler.post {
            teardown()
            detachClient()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XrayVpnService"

        const val TUN_IPV4 = "172.19.0.1"
        const val TUN_DNS_IPV4 = "172.19.0.2"
        const val TUN_IPV6 = "fdfe:dcba:9876::1"
        const val TUN_DNS_IPV6 = "fdfe:dcba:9876::2"
    }
}
