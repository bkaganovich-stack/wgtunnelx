package com.zaneschepke.wireguardautotunnel.xray.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wgtunnelx.xray.XrayRouting
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import org.koin.compose.koinInject

/** Entry point placed in the upstream settings screen (the only UI hook of the fork). */
@Composable
fun XrayRoutingSettingsRow(routing: XrayRouting = koinInject()) {
    val context = LocalContext.current
    val settings by routing.store.settings.collectAsStateWithLifecycle()
    SurfaceRow(
        leading = { Icon(Icons.AutoMirrored.Outlined.AltRoute, contentDescription = null) },
        title = stringResource(R.string.x_routing),
        description = {
            DescriptionText(
                if (settings.enabled)
                    stringResource(
                        R.string.x_routing_desc_on,
                        settings.rules.count { it.enabled },
                        targetLabel(settings.defaultTarget),
                    )
                else stringResource(R.string.x_routing_desc_off)
            )
        },
        onClick = { RoutingActivity.start(context) },
    )
}
