package com.wgtunnelx.xray.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wgtunnelx.xray.XrayRouting

/**
 * Carries the Always-on VPN start from the Xray process to the app process, where the tunnels are
 * managed. Not exported: only [XrayVpnService] sends it.
 */
class XrayAlwaysOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        XrayRouting.get(context).engine.onAlwaysOn()
    }

    companion object {
        const val ACTION = "com.wgtunnelx.xray.ALWAYS_ON"
    }
}
