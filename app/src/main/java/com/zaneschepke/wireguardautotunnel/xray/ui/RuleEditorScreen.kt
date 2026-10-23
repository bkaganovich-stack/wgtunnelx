package com.zaneschepke.wireguardautotunnel.xray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wgtunnelx.xray.config.RuleValidator
import com.wgtunnelx.xray.data.RuleCodec
import com.wgtunnelx.xray.model.RoutingRule
import com.zaneschepke.wireguardautotunnel.R

private val PROTOCOLS = listOf("http", "tls", "quic", "bittorrent")
private val NETWORKS = listOf("", "tcp", "udp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(viewModel: RoutingViewModel, ruleId: String?, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val original =
        remember(ruleId) { state.settings.rules.firstOrNull { it.id == ruleId } ?: RoutingRule() }

    var name by rememberSaveable { mutableStateOf(original.name) }
    var target by rememberSaveable { mutableStateOf(original.target) }
    var domains by remember { mutableStateOf(TextFieldValue(original.domains.joinToString("\n"))) }
    var ips by remember { mutableStateOf(TextFieldValue(original.ips.joinToString("\n"))) }
    var ports by rememberSaveable { mutableStateOf(original.ports) }
    var network by rememberSaveable { mutableStateOf(original.network) }
    var protocols by rememberSaveable { mutableStateOf(original.protocols.toSet()) }
    var packages by rememberSaveable { mutableStateOf(original.packages.toSet()) }
    var pickApps by remember { mutableStateOf(false) }

    val edited =
        original.copy(
            name = name.trim(),
            target = target,
            domains = RuleCodec.splitList(domains.text),
            ips = RuleCodec.splitList(ips.text),
            ports = ports.trim(),
            network = network,
            protocols = protocols.toList(),
            packages = packages.toList(),
        )
    val problems =
        RuleValidator.validateRule(edited, state.geo.geoSite?.codes, state.geo.geoIp?.codes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.x_rule)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.x_back))
                    }
                },
                actions = {
                    if (ruleId != null) {
                        TextButton(
                            onClick = {
                                viewModel.deleteRule(original.id)
                                onDone()
                            }
                        ) {
                            Text(
                                stringResource(R.string.x_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        enabled = !edited.isEmpty,
                        onClick = {
                            viewModel.saveRule(edited)
                            onDone()
                        },
                    ) {
                        Text(stringResource(R.string.x_save))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.x_rule_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.x_rule_target),
                style = MaterialTheme.typography.labelLarge,
            )
            TargetSelector(selected = target, onSelect = { target = it })

            CategoryField(
                label = stringResource(R.string.x_rule_domains),
                hint = stringResource(R.string.x_rule_domains_hint),
                value = domains,
                onValueChange = { domains = it },
                prefix = "geosite:",
                codes = state.geo.geoSite?.codes.orEmpty(),
            )
            CategoryField(
                label = stringResource(R.string.x_rule_ips),
                hint = stringResource(R.string.x_rule_ips_hint),
                value = ips,
                onValueChange = { ips = it },
                prefix = "geoip:",
                codes = state.geo.geoIp?.codes.orEmpty(),
            )
            OutlinedTextField(
                value = ports,
                onValueChange = { ports = it },
                label = { Text(stringResource(R.string.x_rule_ports)) },
                placeholder = { Text(stringResource(R.string.x_rule_ports_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.x_rule_network),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NETWORKS.forEach { value ->
                    FilterChip(
                        selected = network == value,
                        onClick = { network = value },
                        label = {
                            Text(
                                if (value.isEmpty()) stringResource(R.string.x_rule_any)
                                else value.uppercase()
                            )
                        },
                    )
                }
            }

            Text(
                stringResource(R.string.x_rule_protocols),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROTOCOLS.forEach { value ->
                    FilterChip(
                        selected = value in protocols,
                        onClick = {
                            protocols =
                                if (value in protocols) protocols - value else protocols + value
                        },
                        label = { Text(value) },
                    )
                }
            }

            Text(stringResource(R.string.x_rule_apps), style = MaterialTheme.typography.labelLarge)
            HintText(
                if (packages.isEmpty()) stringResource(R.string.x_rule_apps_none)
                else packages.sorted().joinToString(", ")
            )
            HintText(stringResource(R.string.x_rule_apps_hint))
            OutlinedButton(onClick = { pickApps = true }) {
                Text(stringResource(R.string.x_rule_select_apps))
            }

            if (edited.isEmpty) {
                Text(
                    stringResource(R.string.x_rule_empty),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ProblemList(problems)
        }
    }

    if (pickApps) {
        AppPickerDialog(
            viewModel = viewModel,
            selected = packages,
            onDismiss = { pickApps = false },
            onConfirm = {
                packages = it
                pickApps = false
            },
        )
    }
}

/**
 * Multi-line list input that suggests geo categories for the line being typed when it starts with
 * [prefix].
 */
@Composable
private fun CategoryField(
    label: String,
    hint: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    prefix: String,
    codes: Set<String>,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    )
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val lineStart = value.text.lastIndexOf('\n', cursor - 1) + 1
    val currentLine = value.text.substring(lineStart, cursor)
    if (!currentLine.startsWith(prefix) || codes.isEmpty()) return
    val query = currentLine.removePrefix(prefix).lowercase()
    val matches =
        codes
            .asSequence()
            .filter { it.startsWith(query) || (query.length >= 2 && query in it) }
            .sortedWith(compareBy({ !it.startsWith(query) }, { it.length }, { it }))
            .take(12)
            .toList()
    if (matches.isEmpty() || (matches.size == 1 && matches[0] == query)) return
    HintText(stringResource(R.string.x_suggestions))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        matches.forEach { code ->
            AssistChip(
                onClick = {
                    val lineEnd =
                        value.text.indexOf('\n', cursor).let {
                            if (it < 0) value.text.length else it
                        }
                    val replacement = prefix + code
                    val text = value.text.replaceRange(lineStart, lineEnd, replacement)
                    onValueChange(TextFieldValue(text, TextRange(lineStart + replacement.length)))
                },
                label = { Text(code) },
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    viewModel: RoutingViewModel,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadPackages() }
    val packages by viewModel.installedPackages.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(selected) }
    val visible =
        packages
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.packageName !in current }, { it.name.lowercase() }))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_rule_select_apps)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.x_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        val checked = app.packageName in current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth().clickable {
                                    current =
                                        if (checked) current - app.packageName
                                        else current + app.packageName
                                },
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    current =
                                        if (it) current + app.packageName
                                        else current - app.packageName
                                },
                            )
                            Column {
                                Text(app.name, style = MaterialTheme.typography.bodyMedium)
                                HintText(app.packageName)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) { Text(stringResource(R.string.x_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.x_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(viewModel: RoutingViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable {
        mutableStateOf(state.settings.customConfig.ifBlank { viewModel.previewConfig() })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.x_custom_config)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.x_back))
                    }
                },
                actions = {
                    TextButton(onClick = { text = viewModel.previewConfig() }) {
                        Text(stringResource(R.string.x_use_generated))
                    }
                    TextButton(
                        onClick = {
                            viewModel.setCustomConfig(state.settings.useCustomConfig, text)
                            onDone()
                        }
                    ) {
                        Text(stringResource(R.string.x_save))
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(8.dp)) {
            HintText(stringResource(R.string.x_custom_config_desc), Modifier.padding(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
