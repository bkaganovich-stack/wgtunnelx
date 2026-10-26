package com.zaneschepke.wireguardautotunnel.xray.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wgtunnelx.xray.config.XrayConfigBuilder
import com.wgtunnelx.xray.engine.EngineState
import com.wgtunnelx.xray.geo.GeoKind
import com.wgtunnelx.xray.model.DomainStrategy
import com.wgtunnelx.xray.model.GeoSource
import com.wgtunnelx.xray.model.RouteTarget
import com.wgtunnelx.xray.model.RoutingPresets
import com.wgtunnelx.xray.model.RoutingRule
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.util.extensions.asString
import java.text.DateFormat
import java.util.Date

private sealed interface Page {
    data object Main : Page

    data class Rule(val id: String?) : Page

    data object Config : Page
}

@Composable
fun RoutingNavHost(viewModel: RoutingViewModel, onClose: () -> Unit) {
    var pageKey by rememberSaveable { mutableStateOf("main") }
    val page: Page =
        when {
            pageKey == "main" -> Page.Main
            pageKey == "config" -> Page.Config
            pageKey == "rule:" -> Page.Rule(null)
            pageKey.startsWith("rule:") -> Page.Rule(pageKey.removePrefix("rule:"))
            else -> Page.Main
        }
    BackHandler(enabled = page != Page.Main) { pageKey = "main" }
    when (page) {
        Page.Main ->
            RoutingScreen(
                viewModel = viewModel,
                onBack = onClose,
                onEditRule = { id -> pageKey = "rule:" + (id ?: "") },
                onEditConfig = { pageKey = "config" },
            )
        is Page.Rule -> RuleEditorScreen(viewModel, page.id, onDone = { pageKey = "main" })
        Page.Config -> ConfigEditorScreen(viewModel, onDone = { pageKey = "main" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    viewModel: RoutingViewModel,
    onBack: () -> Unit,
    onEditRule: (String?) -> Unit,
    onEditConfig: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = state.settings
    var showPresets by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val geoUpdated = stringResource(R.string.x_geo_updated)
    val importedTemplate = stringResource(R.string.x_imported)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RoutingEvent.ExportReady -> exportJson = event.json
                is RoutingEvent.Message -> {
                    val text =
                        when {
                            event.text == RoutingViewModel.MSG_GEO_UPDATED -> geoUpdated
                            event.text.startsWith(RoutingViewModel.MSG_IMPORTED) -> {
                                val (ok, skipped) =
                                    event.text
                                        .removePrefix(RoutingViewModel.MSG_IMPORTED)
                                        .split('/')
                                importedTemplate.format(ok.toInt(), skipped.toInt())
                            }
                            else -> event.text
                        }
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.x_routing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.x_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                HintText(
                    stringResource(R.string.x_routing_intro),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SwitchRow(
                    title = stringResource(R.string.x_enable_routing),
                    checked = settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                if (settings.enabled && state.tunnelMode != TunnelMode.VPN) {
                    Text(
                        stringResource(
                            R.string.x_requires_vpn_mode,
                            state.tunnelMode.asString(context),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                StatusCard(state, onRestart = viewModel::restartTunnel)
                ProblemList(state.problems, Modifier.padding(top = 8.dp))
            }

            item {
                SectionTitle(stringResource(R.string.x_default_route))
                TargetSelector(
                    selected = settings.defaultTarget,
                    onSelect = viewModel::setDefaultTarget,
                    options = listOf(RouteTarget.TUNNEL, RouteTarget.DIRECT),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                SectionTitle(stringResource(R.string.x_rules))
                HintText(
                    stringResource(R.string.x_rules_hint),
                    Modifier.padding(horizontal = 16.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Button(onClick = { onEditRule(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.x_add_rule))
                    }
                    OutlinedButton(onClick = { showPresets = true }) {
                        Text(stringResource(R.string.x_presets))
                    }
                    OutlinedButton(onClick = { showImport = true }) {
                        Text(stringResource(R.string.x_import))
                    }
                    OutlinedButton(onClick = viewModel::exportRules) {
                        Text(stringResource(R.string.x_export))
                    }
                }
                if (settings.rules.isEmpty()) {
                    HintText(
                        stringResource(R.string.x_no_rules),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(settings.rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    isFirst = rule.id == settings.rules.first().id,
                    isLast = rule.id == settings.rules.last().id,
                    onClick = { onEditRule(rule.id) },
                    onToggle = { viewModel.toggleRule(rule.id, it) },
                    onMove = { viewModel.moveRule(rule.id, it) },
                )
            }

            if (state.tunnels.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.x_tunnels))
                    HintText(
                        stringResource(R.string.x_tunnels_hint),
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(state.tunnels, key = { "tunnel-${it.id}" }) { tunnel ->
                    val checked = settings.tunnelIds.isEmpty() || tunnel.id in settings.tunnelIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { viewModel.setTunnelSelected(tunnel.id, !checked) }
                                .padding(horizontal = 8.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.setTunnelSelected(tunnel.id, it) },
                        )
                        Text(tunnel.name)
                    }
                }
            }

            item { GeoSection(state, viewModel) }
            item { DnsSection(state, viewModel) }
            item {
                AdvancedSection(
                    state,
                    viewModel,
                    onEditConfig = onEditConfig,
                    onPreview = { showPreview = true },
                )
            }
        }
    }

    if (showPresets) {
        PresetDialog(
            onDismiss = { showPresets = false },
            onSelect = {
                viewModel.applyPreset(it)
                showPresets = false
            },
        )
    }
    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text, replace ->
                viewModel.importRules(text, replace)
                showImport = false
            },
        )
    }
    exportJson?.let { json -> TextDialog(json, onDismiss = { exportJson = null }) }
    if (showPreview) TextDialog(viewModel.previewConfig(), onDismiss = { showPreview = false })
}

@Composable
private fun StatusCard(state: RoutingUiState, onRestart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.x_status), style = MaterialTheme.typography.labelLarge)
            val status =
                when (val e = state.engine) {
                    EngineState.Stopped -> stringResource(R.string.x_status_stopped)
                    is EngineState.Starting -> stringResource(R.string.x_status_starting)
                    is EngineState.Running ->
                        stringResource(R.string.x_status_running, e.coreVersion)
                    is EngineState.Failed -> stringResource(R.string.x_status_failed, e.message)
                    is EngineState.Revoked -> stringResource(R.string.x_status_revoked)
                }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (state.engine is EngineState.Running) {
                val t = state.traffic
                fun up(tag: String) = formatBytes(t[tag]?.uplink ?: 0)
                fun down(tag: String) = formatBytes(t[tag]?.downlink ?: 0)
                val blocked = (t[XrayConfigBuilder.TAG_BLOCK]?.let { it.uplink + it.downlink } ?: 0)
                HintText(
                    stringResource(
                        R.string.x_traffic,
                        up(XrayConfigBuilder.TAG_PROXY),
                        down(XrayConfigBuilder.TAG_PROXY),
                        up(XrayConfigBuilder.TAG_DIRECT),
                        down(XrayConfigBuilder.TAG_DIRECT),
                        formatBytes(blocked),
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HintText(stringResource(R.string.x_apply_restart), Modifier.weight(1f))
                    TextButton(onClick = onRestart) { Text(stringResource(R.string.x_restart_now)) }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: RoutingRule,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    targetLabel(rule.target),
                    color = targetColor(rule.target),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    rule.name.ifBlank { stringResource(R.string.x_rule) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val summary =
                (rule.domains +
                        rule.ips +
                        listOfNotNull(
                            rule.ports.takeIf { it.isNotBlank() }?.let { "port:$it" },
                            rule.network.takeIf { it.isNotBlank() },
                        ) +
                        rule.protocols +
                        rule.packages)
                    .joinToString(", ")
            HintText(summary, Modifier.padding(top = 2.dp))
        }
        Column {
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.x_move_up))
            }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.x_move_down))
            }
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun GeoSection(state: RoutingUiState, viewModel: RoutingViewModel) {
    val geo = state.geo
    val options = state.settings.geo
    SectionTitle(stringResource(R.string.x_geo))
    DropdownRow(
        title = stringResource(R.string.x_geo_source),
        selected = options.source,
        options = GeoSource.entries,
        label = { geoSourceLabel(it) },
        onSelect = { source -> viewModel.setGeo { it.copy(source = source) } },
    )
    if (options.source == GeoSource.CUSTOM) {
        var ip by remember(options.customGeoIpUrl) { mutableStateOf(options.customGeoIpUrl) }
        var site by remember(options.customGeoSiteUrl) { mutableStateOf(options.customGeoSiteUrl) }
        OutlinedTextField(
            value = site,
            onValueChange = {
                site = it
                viewModel.setGeo { g -> g.copy(customGeoSiteUrl = it) }
            },
            label = { Text("geosite.dat URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = ip,
            onValueChange = {
                ip = it
                viewModel.setGeo { g -> g.copy(customGeoIpUrl = it) }
            },
            label = { Text("geoip.dat URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        GeoKind.entries.forEach { kind ->
            val info = geo.info(kind)
            HintText(
                if (info == null) stringResource(R.string.x_geo_missing, kind.fileName)
                else
                    stringResource(
                        R.string.x_geo_info,
                        kind.fileName,
                        formatBytes(info.sizeBytes),
                        info.codes.size,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(info.updatedAtMillis)),
                    )
            )
        }
        geo.downloading?.let { kind ->
            HintText(stringResource(R.string.x_geo_downloading, kind.fileName))
            val progress = geo.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        geo.lastError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = viewModel::updateGeo,
            enabled = geo.downloading == null,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.x_geo_update))
        }
    }
    DropdownRow(
        title = stringResource(R.string.x_geo_auto),
        selected = options.autoUpdateDays,
        options = listOf(0, 1, 3, 7, 14, 30),
        label = {
            if (it == 0) stringResource(R.string.x_geo_auto_off)
            else stringResource(R.string.x_geo_auto_days, it)
        },
        onSelect = { days -> viewModel.setGeo { it.copy(autoUpdateDays = days) } },
    )
}

@Composable
private fun geoSourceLabel(source: GeoSource): String =
    when (source) {
        GeoSource.RUNETFREEDOM -> stringResource(R.string.x_geo_source_runetfreedom)
        GeoSource.LOYALSOLDIER -> stringResource(R.string.x_geo_source_loyalsoldier)
        GeoSource.V2FLY -> stringResource(R.string.x_geo_source_v2fly)
        GeoSource.CUSTOM -> stringResource(R.string.x_geo_source_custom)
    }

@Composable
private fun DnsSection(state: RoutingUiState, viewModel: RoutingViewModel) {
    val dns = state.settings.dns
    SectionTitle(stringResource(R.string.x_dns))
    var remote by remember(dns.remote) { mutableStateOf(dns.remote) }
    var direct by remember(dns.direct) { mutableStateOf(dns.direct) }
    OutlinedTextField(
        value = remote,
        onValueChange = {
            remote = it
            viewModel.setDns { d -> d.copy(remote = it.trim()) }
        },
        label = { Text(stringResource(R.string.x_dns_remote)) },
        supportingText = { Text(stringResource(R.string.x_dns_remote_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        value = direct,
        onValueChange = {
            direct = it
            viewModel.setDns { d -> d.copy(direct = it.trim()) }
        },
        label = { Text(stringResource(R.string.x_dns_direct)) },
        supportingText = { Text(stringResource(R.string.x_dns_direct_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    SwitchRow(
        title = stringResource(R.string.x_fake_dns),
        description = stringResource(R.string.x_fake_dns_desc),
        checked = dns.fakeDns,
        onCheckedChange = { value -> viewModel.setDns { it.copy(fakeDns = value) } },
    )
}

@Composable
private fun AdvancedSection(
    state: RoutingUiState,
    viewModel: RoutingViewModel,
    onEditConfig: () -> Unit,
    onPreview: () -> Unit,
) {
    val s = state.settings
    SectionTitle(stringResource(R.string.x_advanced))
    SwitchRow(
        title = stringResource(R.string.x_sniffing),
        description = stringResource(R.string.x_sniffing_desc),
        checked = s.sniffing,
        onCheckedChange = viewModel::setSniffing,
    )
    SwitchRow(
        title = stringResource(R.string.x_bypass_lan),
        description = stringResource(R.string.x_bypass_lan_desc),
        checked = s.bypassLan,
        onCheckedChange = viewModel::setBypassLan,
    )
    SwitchRow(
        title = stringResource(R.string.x_block_quic),
        description = stringResource(R.string.x_block_quic_desc),
        checked = s.blockQuicInTunnel,
        onCheckedChange = viewModel::setBlockQuicInTunnel,
    )
    DropdownRow(
        title = stringResource(R.string.x_domain_strategy),
        selected = s.domainStrategy,
        options = DomainStrategy.entries,
        label = { it.xray },
        onSelect = viewModel::setDomainStrategy,
    )
    DropdownRow(
        title = stringResource(R.string.x_log_level),
        selected = s.logLevel,
        options = listOf("debug", "info", "warning", "error", "none"),
        label = { it },
        onSelect = viewModel::setLogLevel,
    )
    SwitchRow(
        title = stringResource(R.string.x_custom_config),
        description = stringResource(R.string.x_custom_config_desc),
        checked = s.useCustomConfig,
        onCheckedChange = { use ->
            val text = s.customConfig.ifBlank { viewModel.previewConfig() }
            viewModel.setCustomConfig(use, text)
        },
    )
    ClickRow(title = stringResource(R.string.x_edit_config), onClick = onEditConfig)
    ClickRow(title = stringResource(R.string.x_view_config), onClick = onPreview)
}

@Composable
private fun PresetDialog(onDismiss: () -> Unit, onSelect: (RoutingPresets) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_presets)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HintText(stringResource(R.string.x_preset_replace_warning))
                RoutingPresets.entries.forEach { preset ->
                    Text(
                        presetLabel(preset),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onSelect(preset) }
                                .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.x_cancel)) }
        },
    )
}

@Composable
private fun presetLabel(preset: RoutingPresets): String =
    when (preset) {
        RoutingPresets.RUSSIA_DIRECT -> stringResource(R.string.x_preset_RUSSIA_DIRECT)
        RoutingPresets.RUSSIA_BLOCKED_ONLY -> stringResource(R.string.x_preset_RUSSIA_BLOCKED_ONLY)
        RoutingPresets.TUNNEL_ALL_BLOCK_ADS ->
            stringResource(R.string.x_preset_TUNNEL_ALL_BLOCK_ADS)
        RoutingPresets.DIRECT_ALL_EXCEPT_LISTED ->
            stringResource(R.string.x_preset_DIRECT_ALL_EXCEPT_LISTED)
    }

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(clipboardText(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_import)) },
        text = {
            Column {
                HintText(stringResource(R.string.x_import_hint))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                            .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onImport(text, true) }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.x_import_replace))
                }
                TextButton(onClick = { onImport(text, false) }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.x_import_append))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.x_cancel)) }
        },
    )
}

@Composable
private fun TextDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.x_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("WG Tunnel X", text))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.x_copy))
                }
                TextButton(
                    onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                ) {
                    Text(stringResource(R.string.x_share))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.x_back)) }
        },
    )
}

private fun clipboardText(context: Context): String =
    context
        .getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.takeIf { it.trimStart().startsWith("[") || it.trimStart().startsWith("{") }
        .orEmpty()
