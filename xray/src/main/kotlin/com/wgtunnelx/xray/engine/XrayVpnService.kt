package com.wgtunnelx.xray.engine

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * The VPN that carries routed traffic: a TUN whose packets are read by Xray-core directly (Xray
 * `tun` inbound, the fd is handed over with `xray.tun.fd`).
 *
 * This app's own package is always excluded from the TUN. That keeps the WireGuard UDP socket of
 * the userspace tunnel and Xray's `direct` sockets on the underlying network without any socket
 * protection, and it makes routing loops impossible.
 */
class XrayVpnService : VpnService() {

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var activeTunnelId: Int? = null

    // loading geo files can take a moment, keep it off the main thread
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xray-vpn") }

    private val engine: XrayEngine?
        get() = XrayEngine.instance

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> worker.execute { startRouting() }
            SERVICE_INTERFACE -> {
                // Always-on VPN: let the app start its default tunnel the regular way
                Timber.tag(TAG).i("Started by the system (Always-on VPN)")
                engine?.onAlwaysOn()
                // the tunnel start that follows decides which VPN service owns the interface
                if (tunFd == null) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startRouting() {
        val engine = engine ?: return stopSelf()
        val request = engine.pendingRequest ?: return stopSelf()
        activeTunnelId = request.tunnelId
        try {
            if (prepare(this) != null) {
                throw IllegalStateException("VPN permission is not granted")
            }
            val fd = establish(request)
            tunFd?.close()
            tunFd = fd
            engine.core.start(request.configJson, fd.fd)
            engine.report(
                EngineState.Running(
                    request.tunnelId,
                    System.currentTimeMillis(),
                    engine.core.version,
                )
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to start routing")
            teardown()
            engine.report(EngineState.Failed(request.tunnelId, e.message ?: e.toString()))
            stopSelf()
        }
    }

    private fun establish(request: RoutingRequest): ParcelFileDescriptor {
        val builder =
            Builder()
                .setSession(request.sessionName)
                .setMtu(request.params.mtu)
                .addAddress(TUN_IPV4, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_DNS_IPV4)
                // IPv6 is always captured so it cannot leak around the routing rules
                .addAddress(TUN_IPV6, 126)
                .addRoute("::", 0)
                .addDnsServer(TUN_DNS_IPV6)
        request.configureIntent?.let { builder.setConfigureIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(request.metered)
        builder.setUnderlyingNetworks(null)

        val self = packageName
        val included = request.includedApps.filter { it != self }
        if (included.isNotEmpty()) {
            included.forEach { pkg -> builder.allowApp(pkg) }
        } else {
            builder.addDisallowedApplication(self)
            request.excludedApps.filter { it != self }.forEach { pkg -> builder.disallowApp(pkg) }
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

    /** Stops Xray and closes the TUN. */
    internal fun stopRouting(userInitiated: Boolean) {
        Timber.tag(TAG).i("Stopping routing (user=%s)", userInitiated)
        teardown()
        stopSelf()
    }

    @Synchronized
    private fun teardown() {
        engine?.core?.stop()
        runCatching { tunFd?.close() }.onFailure { Timber.tag(TAG).w(it, "close tun") }
        tunFd = null
    }

    override fun onRevoke() {
        Timber.tag(TAG).w("Routing VPN revoked")
        val id = activeTunnelId
        teardown()
        engine?.report(EngineState.Revoked(id))
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        worker.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XrayVpnService"
        const val ACTION_START = "com.wgtunnelx.xray.START"

        const val TUN_IPV4 = "172.19.0.1"
        const val TUN_DNS_IPV4 = "172.19.0.2"
        const val TUN_IPV6 = "fdfe:dcba:9876::1"
        const val TUN_DNS_IPV6 = "fdfe:dcba:9876::2"

        @Volatile
        internal var instance: XrayVpnService? = null
            private set
    }
}
