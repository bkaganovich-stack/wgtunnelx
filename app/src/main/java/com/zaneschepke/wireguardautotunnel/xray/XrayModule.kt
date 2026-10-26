package com.zaneschepke.wireguardautotunnel.xray

import com.wgtunnelx.xray.XrayRouting
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelBackendProvider
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.di.Dispatcher
import com.zaneschepke.wireguardautotunnel.di.Scope
import com.zaneschepke.wireguardautotunnel.xray.ui.RoutingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * WG Tunnel X additions. Loaded after the upstream modules, so the [TunnelProvider] definition here
 * overrides the upstream one and wraps it.
 */
val xrayModule = module {
    single { XrayRouting.get(androidContext()) }

    single<TunnelProvider> {
        RoutingTunnelProvider(
            delegate =
                TunnelBackendProvider(
                    get(),
                    get(named(Scope.APPLICATION)),
                    get(named(Dispatcher.IO)),
                ),
            routing = get(),
            appProvider = get(),
            context = androidContext(),
            scope = get(named(Scope.APPLICATION)),
        )
    }

    viewModelOf(::RoutingViewModel)

    single(createdAtStart = true) {
        AutoTunnelWatchdog(
                context = androidContext(),
                stateHolder = get(),
                settings = get(),
                coordinator = get(),
                networkEngine = { get() },
                tunnelCoordinator = { get<TunnelCoordinator>() },
                scope = get(named(Scope.APPLICATION)),
            )
            .also { it.start() }
    }

    single(createdAtStart = true) {
        RoutedTunnelHealer(
                context = androidContext(),
                backend = { get() },
                engine = get<XrayRouting>().engine,
                scope = get(named(Scope.APPLICATION)),
            )
            .also { it.start() }
    }
}
