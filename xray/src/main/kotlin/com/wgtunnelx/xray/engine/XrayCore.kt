package com.wgtunnelx.xray.engine

import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetSocketAddress
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import timber.log.Timber

/** Thin, thread-safe wrapper around the gomobile `libv2ray` (Xray-core) binding. */
internal class XrayCore(assetDir: String, private val connectivity: ConnectivityManager?) {

    private val controller: CoreController

    init {
        // geoip.dat / geosite.dat are looked up in assetDir
        Libv2ray.initCoreEnv(assetDir, "")
        controller =
            Libv2ray.newCoreController(
                object : CoreCallbackHandler {
                    override fun startup(): Long = 0

                    override fun shutdown(): Long = 0

                    override fun onEmitStatus(code: Long, message: String?): Long {
                        Timber.tag(TAG).d("core status %d: %s", code, message)
                        return 0
                    }
                }
            )
        if (connectivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            controller.registerProcessFinder(UidFinder(connectivity))
        }
    }

    val isRunning: Boolean
        get() = controller.isRunning

    val version: String by lazy { runCatching { Libv2ray.checkVersionX() }.getOrDefault("?") }

    @Synchronized
    fun start(configJson: String, tunFd: Int) {
        if (controller.isRunning) controller.stopLoop()
        controller.startLoop(configJson, tunFd)
        if (!controller.isRunning) throw IllegalStateException("Xray core did not start")
    }

    @Synchronized
    fun stop() {
        runCatching { if (controller.isRunning) controller.stopLoop() }
            .onFailure { Timber.tag(TAG).e(it, "stopLoop failed") }
    }

    /** Returns and resets the per-outbound counters since the previous call. */
    fun drainTraffic(): Map<String, OutboundTraffic> {
        val raw = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull()
        if (raw.isNullOrEmpty()) return emptyMap()
        val up = HashMap<String, Long>()
        val down = HashMap<String, Long>()
        raw.split(';').forEach { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            when (parts[1]) {
                "uplink" -> up.merge(parts[0], value, Long::plus)
                "downlink" -> down.merge(parts[0], value, Long::plus)
            }
        }
        return (up.keys + down.keys).associateWith { OutboundTraffic(up[it] ?: 0L, down[it] ?: 0L) }
    }

    /** Lets Xray's `process` rule matcher identify the app (UID) owning a TUN connection. */
    private class UidFinder(private val cm: ConnectivityManager) : ProcessFinder {
        override fun findProcessByConnection(
            network: String?,
            srcIP: String?,
            srcPort: Long,
            destIP: String?,
            destPort: Long,
        ): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            return try {
                val protocol =
                    if (network == "udp") OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                cm.getConnectionOwnerUid(
                        protocol,
                        InetSocketAddress(srcIP, srcPort.toInt()),
                        InetSocketAddress(destIP, destPort.toInt()),
                    )
                    .toLong()
            } catch (e: Exception) {
                -1
            }
        }
    }

    companion object {
        const val TAG = "XrayCore"
    }
}
