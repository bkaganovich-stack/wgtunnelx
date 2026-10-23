package com.wgtunnelx.xray

import android.content.Context
import com.wgtunnelx.xray.data.RoutingSettingsStore
import com.wgtunnelx.xray.engine.XrayEngine
import com.wgtunnelx.xray.geo.GeoDataManager

/** Entry point of the routing module; one instance per process. */
class XrayRouting private constructor(context: Context) {

    val store = RoutingSettingsStore(context)
    val geo = GeoDataManager(context)
    val engine = XrayEngine(context, geo)

    companion object {
        @Volatile private var instance: XrayRouting? = null

        fun get(context: Context): XrayRouting =
            instance
                ?: synchronized(this) {
                    instance ?: XrayRouting(context.applicationContext).also { instance = it }
                }
    }
}
