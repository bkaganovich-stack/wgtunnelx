package com.zaneschepke.wireguardautotunnel.xray.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wgtunnelx.xray.XrayRouting
import com.wgtunnelx.xray.config.RuleProblem
import com.wgtunnelx.xray.config.RuleValidator
import com.wgtunnelx.xray.config.SessionParams
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.data.RuleCodec
import com.wgtunnelx.xray.engine.EngineState
import com.wgtunnelx.xray.engine.OutboundTraffic
import com.wgtunnelx.xray.geo.GeoKind
import com.wgtunnelx.xray.geo.GeoState
import com.wgtunnelx.xray.geo.GeoUpdateWorker
import com.wgtunnelx.xray.model.DnsOptions
import com.wgtunnelx.xray.model.DomainStrategy
import com.wgtunnelx.xray.model.GeoOptions
import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingPresets
import com.wgtunnelx.xray.model.RoutingRule
import com.wgtunnelx.xray.model.RoutingSettings
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.InstalledPackage
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.InstalledPackageRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.ui.theme.Theme
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TunnelItem(val id: Int, val name: String)

data class RoutingUiState(
    val settings: RoutingSettings = RoutingSettings(),
    val geo: GeoState = GeoState(),
    val engine: EngineState = EngineState.Stopped,
    val traffic: Map<String, OutboundTraffic> = emptyMap(),
    val tunnels: List<TunnelItem> = emptyList(),
    val tunnelMode: TunnelMode = TunnelMode.VPN,
    val theme: Theme = Theme.AUTOMATIC,
) {
    val problems: List<RuleProblem>
        get() = RuleValidator.validate(settings, geo.geoSite?.codes, geo.geoIp?.codes)

    /** True when saved settings differ from what the running session was started with. */
    val isRunning: Boolean
        get() = engine is EngineState.Running
}

sealed interface RoutingEvent {
    data class Message(val text: String) : RoutingEvent

    data class ExportReady(val json: String) : RoutingEvent
}

class RoutingViewModel(
    application: Application,
    private val routing: XrayRouting,
    private val tunnelRepository: TunnelRepository,
    private val packageRepository: InstalledPackageRepository,
    private val coordinator: TunnelCoordinator,
    settingsRepository: GeneralSettingRepository,
) : AndroidViewModel(application) {

    private val store = routing.store

    private val _events = MutableSharedFlow<RoutingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RoutingEvent> = _events.asSharedFlow()

    private val routingFlows =
        combine(store.settings, routing.geo.state, routing.engine.state, routing.engine.traffic) {
            settings,
            geo,
            engine,
            traffic ->
            RoutingUiState(settings = settings, geo = geo, engine = engine, traffic = traffic)
        }

    val uiState: StateFlow<RoutingUiState> =
        combine(
                routingFlows,
                tunnelRepository.userTunnelsFlow.map { list ->
                    list.map { TunnelItem(it.id, it.name) }
                },
                settingsRepository.flow,
            ) { state, tunnels, general ->
                state.copy(
                    tunnels = tunnels,
                    tunnelMode = general.tunnelMode,
                    theme = general.theme,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutingUiState())

    val installedPackages: StateFlow<List<InstalledPackage>> = packageRepository.installedPackages

    init {
        viewModelScope.launch { routing.geo.refreshState() }
        viewModelScope.launch {
            while (isActive) {
                routing.engine.refreshTraffic()
                delay(2.seconds)
            }
        }
    }

    fun loadPackages() = viewModelScope.launch { packageRepository.getInstalledPackages() }

    private fun edit(transform: (RoutingSettings) -> RoutingSettings) = viewModelScope.launch {
        store.update(transform)
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        store.update { it.copy(enabled = enabled) }
        GeoUpdateWorker.schedule(
            getApplication(),
            if (enabled) store.current.geo.autoUpdateDays else 0,
        )
        if (enabled && store.current.rules.any { r -> r.usesGeo() }) {
            val geo = routing.geo.state.value
            if (geo.geoSite == null || geo.geoIp == null) updateGeo()
        }
    }

    fun setDefaultTarget(target: RouteTarget) = edit { it.copy(defaultTarget = target) }

    fun setTunnelSelected(id: Int, selected: Boolean) = edit { s ->
        val all = uiState.value.tunnels.map { it.id }.toSet()
        val current = s.tunnelIds.ifEmpty { all }
        val next = if (selected) current + id else current - id
        // every tunnel selected is stored as "all", which also covers tunnels added later
        s.copy(tunnelIds = if (next.containsAll(all)) emptySet() else next)
    }

    fun applyPreset(preset: RoutingPresets) = edit {
        it.copy(defaultTarget = preset.defaultTarget, rules = preset.rules)
    }

    fun saveRule(rule: RoutingRule) = edit { s ->
        val exists = s.rules.any { it.id == rule.id }
        s.copy(
            rules =
                if (exists) s.rules.map { if (it.id == rule.id) rule else it } else s.rules + rule
        )
    }

    fun deleteRule(id: String) = edit { s -> s.copy(rules = s.rules.filterNot { it.id == id }) }

    fun toggleRule(id: String, enabled: Boolean) = edit { s ->
        s.copy(rules = s.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun moveRule(id: String, delta: Int) = edit { s ->
        val index = s.rules.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in s.rules.indices) return@edit s
        val list = s.rules.toMutableList()
        list.add(target, list.removeAt(index))
        s.copy(rules = list)
    }

    fun setDns(transform: (DnsOptions) -> DnsOptions) = edit { it.copy(dns = transform(it.dns)) }

    fun setGeo(transform: (GeoOptions) -> GeoOptions) = viewModelScope.launch {
        store.update { it.copy(geo = transform(it.geo)) }
        if (store.current.enabled) {
            GeoUpdateWorker.schedule(getApplication(), store.current.geo.autoUpdateDays)
        }
    }

    fun setSniffing(value: Boolean) = edit { it.copy(sniffing = value) }

    fun setBypassLan(value: Boolean) = edit { it.copy(bypassLan = value) }

    fun setBlockQuicInTunnel(value: Boolean) = edit { it.copy(blockQuicInTunnel = value) }

    fun setDomainStrategy(value: DomainStrategy) = edit { it.copy(domainStrategy = value) }

    fun setLogLevel(value: String) = edit { it.copy(logLevel = value) }

    fun setCustomConfig(use: Boolean, text: String) = edit {
        it.copy(useCustomConfig = use, customConfig = text)
    }

    fun updateGeo() = viewModelScope.launch {
        routing.geo
            .updateAll(store.current.geo)
            .onSuccess { _events.emit(RoutingEvent.Message(MSG_GEO_UPDATED)) }
            .onFailure { _events.emit(RoutingEvent.Message(it.message ?: it.toString())) }
    }

    fun updateGeoFile(kind: GeoKind) = viewModelScope.launch {
        val geo = store.current.geo
        routing.geo.update(kind, if (kind == GeoKind.IP) geo.geoIpUrl else geo.geoSiteUrl)
    }

    fun importRules(text: String, replace: Boolean) = viewModelScope.launch {
        runCatching { RuleCodec.import(text) }
            .onSuccess { result ->
                store.update { s ->
                    s.copy(rules = if (replace) result.rules else s.rules + result.rules)
                }
                _events.emit(
                    RoutingEvent.Message("$MSG_IMPORTED${result.rules.size}/${result.skipped}")
                )
            }
            .onFailure { _events.emit(RoutingEvent.Message(it.message ?: it.toString())) }
    }

    fun exportRules() = viewModelScope.launch {
        _events.emit(RoutingEvent.ExportReady(RuleCodec.export(store.current.rules)))
    }

    /** The config that would be generated now, with placeholder proxy credentials. */
    fun previewConfig(): String = runCatching {
        XrayConfigBuilder.build(
            store.current.copy(useCustomConfig = false),
            SessionParams(
                socksPort = 10808,
                socksUser = XrayConfigBuilder.PLACEHOLDER_USER,
                socksPass = XrayConfigBuilder.PLACEHOLDER_PASS,
            ),
        )
    }
        .map { it.replace("10808", XrayConfigBuilder.PLACEHOLDER_PORT) }
        .getOrElse { it.toString() }

    fun restartTunnel() = viewModelScope.launch { coordinator.restartActiveTunnels() }

    private fun RoutingRule.usesGeo() =
        domains.any { it.startsWith("geosite:") } || ips.any { it.startsWith("geoip:") }

    companion object {
        // markers resolved to string resources by the screen
        const val MSG_GEO_UPDATED = "@geo_updated"
        const val MSG_IMPORTED = "@imported:"
    }
}
