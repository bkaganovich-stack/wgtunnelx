package com.zaneschepke.wireguardautotunnel.xray.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.theme.WireguardAutoTunnelTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Host of the routing settings. A separate activity keeps the fork out of the upstream navigation
 * graph; it uses the app theme so it looks like the rest of WG Tunnel.
 */
class RoutingActivity : ComponentActivity() {

    private val viewModel: RoutingViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalIsAndroidTV provides isTv) {
                WireguardAutoTunnelTheme(theme = state.theme) {
                    RoutingNavHost(viewModel = viewModel, onClose = ::finish)
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RoutingActivity::class.java))
        }
    }
}
