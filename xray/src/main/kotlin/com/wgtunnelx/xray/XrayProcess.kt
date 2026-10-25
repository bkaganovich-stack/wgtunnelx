package com.wgtunnelx.xray

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import timber.log.Timber

/**
 * Xray-core runs in its own process (`:xray`, see [com.wgtunnelx.xray.engine.XrayVpnService]).
 *
 * AmneziaWG and Xray-core are both Go libraries with their own Go runtime. Two Go runtimes in one
 * process share the signal handlers, and a fault in one of them can be taken by the other for its
 * own: Xray then dies with `fatal error: unknown caller pc` and the whole process hangs with the
 * VPN up and no traffic. With one runtime per process this cannot happen.
 */
object XrayProcess {
    const val SUFFIX = ":xray"

    @Volatile private var cached: Boolean? = null

    fun isXray(context: Context): Boolean =
        cached ?: (processName(context)?.endsWith(SUFFIX) == true).also { cached = it }

    /**
     * Called first in `Application.onCreate`. Returns true in the Xray process, where the regular app
     * startup must not run: it would load the AmneziaWG runtime next to Xray's.
     */
    fun isolate(app: Application): Boolean {
        if (!isXray(app)) return false
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Timber.plant(if (debuggable) Timber.DebugTree() else InfoTree())
        return true
    }

    private fun processName(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }
                .getOrNull()
                ?.takeIf { it.startsWith(context.packageName) }
        }

    private class InfoTree : Timber.DebugTree() {
        override fun isLoggable(tag: String?, priority: Int) = priority >= Log.INFO
    }
}
