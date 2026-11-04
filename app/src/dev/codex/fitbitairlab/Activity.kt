@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codex.fitbitairlab

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

class LabActivity : ComponentActivity() {
    private lateinit var model: SignalsModel
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { model.refresh(); handler.postDelayed(this, 1000) } }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SignalsModel::class.java]
        setContent {
            SignalsTheme {
                SignalsApp(model,
                    allowBluetooth = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    allowStatus = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    openBluetooth = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    openAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, AirNotificationListener::class.java).flattenToString()))
                    })
            }
        }
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}

@Composable
internal fun SignalsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

private data class Destination(val key: String, val label: Int, val icon: ImageVector)
private val destinations = listOf(Destination("home", R.string.home, Icons.Default.Home), Destination("apps", R.string.apps, Icons.Default.Menu), Destination("patterns", R.string.patterns, Icons.Default.Notifications))

@Composable
private fun SignalsApp(model: SignalsModel, allowBluetooth: () -> Unit, allowStatus: () -> Unit, openBluetooth: () -> Unit, openAccess: () -> Unit) {
    val state = model.state
    val focus = LocalFocusManager.current
    var page by rememberSaveable { mutableStateOf("home") }
    var draft by rememberSaveable { mutableStateOf<Map<String, Int>>(state.rules.toMap()) }
    var query by rememberSaveable { mutableStateOf("") }
    var assignment by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by rememberSaveable { mutableStateOf<Int?>(null) }
    var preview by rememberSaveable { mutableStateOf<Int?>(null) }
    var accessDialog by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val notice = model.notice
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.consumeNotice(it) } }
    LaunchedEffect(page) { if (page == "apps") model.loadApps() }
    BackHandler(enabled = page != "home" && assignment == null && edit == null && preview == null && !accessDialog) { page = "home" }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val rail = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
                Spacer(Modifier.height(20.dp))
                destinations.forEach { destination ->
                    NavigationRailItem(selected = page == destination.key, onClick = { page = destination.key },
                        icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.label)) })
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(title = { Text(stringResource(if (page == "home") R.string.app_name else destinations.first { it.key == page }.label)) },
                        actions = {
                            if (page == "apps") TextButton(enabled = draft != state.rules, onClick = { model.saveApps(draft) }) { Text(stringResource(R.string.save)) }
                        })
                },
                bottomBar = {
                    if (!rail) NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(selected = page == destination.key, onClick = { page = destination.key },
                                icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.label)) })
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                    when (page) {
                        "home" -> HomeScreen(model, Modifier.widthIn(max = 720.dp), onApps = { page = "apps" },
                            allowBluetooth, allowStatus, openBluetooth, { accessDialog = true })
                        "patterns" -> PatternsScreen(model, Modifier.widthIn(max = 720.dp), { preview = it }, { edit = it })
                        "apps" -> AppsScreen(model, draft, query, Modifier.widthIn(max = 840.dp),
                            onQuery = { query = it }, onApp = { focus.clearFocus(); assignment = it }, onDiscard = { draft = state.rules.toMap() })
                    }
                }
            }
        }
    }
    if (accessDialog) AlertDialog(onDismissRequest = { accessDialog = false }, title = { Text(stringResource(R.string.notification_access)) },
        text = { Text(stringResource(R.string.access_explanation)) },
        confirmButton = { TextButton(onClick = { accessDialog = false; openAccess() }) { Text(stringResource(R.string.open_settings)) } },
        dismissButton = { TextButton(onClick = { accessDialog = false }) { Text(stringResource(R.string.cancel)) } })
    assignment?.let { pkg ->
        ModalBottomSheet(onDismissRequest = { assignment = null }) {
            Column(Modifier.fillMaxWidth().selectableGroup().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.assign_pattern, model.label(pkg)), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                for (profile in 0..3) {
                    val selected = (draft[pkg] ?: 0) == profile
                    val title = if (profile == 0) stringResource(R.string.off) else stringResource(R.string.signal_name, Rules.profileName(profile))
                    Surface(shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().testTag("assign-$profile").selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (profile != 0 && !draft.containsKey(pkg) && draft.size >= 5) model.message(R.string.app_limit)
                            else { draft = draft.toMutableMap().apply { if (profile == 0) remove(pkg) else put(pkg, profile) }.toMap(); assignment = null }
                        }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RadioButton(selected, null)
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                if (profile != 0) Text(groupsText(state.profiles[profile - 1].groups), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    edit?.let { profile -> PatternEditor(profile, state.profiles[profile - 1], onDismiss = { edit = null }, onSave = { groups, gap -> model.saveProfile(profile, groups, gap); edit = null }) }
    preview?.let { profile ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(stringResource(R.string.preview_title, Rules.profileName(profile))) },
            text = { Text(stringResource(R.string.preview_body)) },
            confirmButton = { TextButton(onClick = { preview = null; model.command("pattern", profile) }) { Text(stringResource(R.string.preview)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable private fun groupsText(count: Int) = pluralStringResource(R.plurals.group_count, count, count)
@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

@Composable
private fun HomeScreen(model: SignalsModel, modifier: Modifier, onApps: () -> Unit, allowBluetooth: () -> Unit, allowStatus: () -> Unit, openBluetooth: () -> Unit, openAccess: () -> Unit) {
    val state = model.state
    LazyColumn(modifier.fillMaxHeight(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.fitbit_air), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(if (state.running) R.string.running_title else R.string.stopped_title), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(if (state.running) R.string.running_body else R.string.stopped_body), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { if (state.running) model.stop() else model.startAlerts() },
                        enabled = state.running || (state.ready && !model.busy), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (state.running) R.string.stop_alerts else R.string.start_alerts))
                    }
                    if (!state.running && !state.ready) Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.bodyMedium)
                    if (!state.running && state.lastStatus.isNotBlank()) Text(stringResource(R.string.pause_reason, state.lastStatus), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.setup))
                OutlinedCard {
                    AccessRow(stringResource(R.string.bluetooth), stringResource(when { !state.bluetoothAllowed -> R.string.bluetooth_needed; !state.bluetoothOn -> R.string.bluetooth_off; else -> R.string.bluetooth_ready }), state.bluetoothAllowed && state.bluetoothOn, if (state.bluetoothAllowed) R.string.settings else R.string.allow, if (state.bluetoothAllowed) openBluetooth else allowBluetooth)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    AccessRow(stringResource(R.string.notification_access), stringResource(if (state.listenerAllowed) R.string.access_ready else R.string.access_needed), state.listenerAllowed, R.string.allow, openAccess)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    AccessRow(stringResource(R.string.status_notification), stringResource(if (state.notificationsAllowed) R.string.status_ready else R.string.status_needed), state.notificationsAllowed, R.string.allow, allowStatus)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.your_apps))
                if (state.rules.isEmpty()) OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.no_apps), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.no_apps_body))
                        FilledTonalButton(onClick = onApps) { Text(stringResource(R.string.choose_apps)) }
                    }
                } else OutlinedCard(Modifier.fillMaxWidth()) {
                    state.rules.forEach { (pkg, profile) ->
                        ListItem(headlineContent = { Text(model.label(pkg)) }, supportingContent = { Text(groupsText(state.profiles[profile - 1].groups)) }, trailingContent = { SignalBadge(Rules.profileName(profile)) })
                    }
                    TextButton(onClick = onApps, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.manage)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.privacy_title))
                Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.device_checks)) }
                if (expanded) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.device_checks_help))
                    TextButton(enabled = !state.running && !model.busy && state.bluetoothOn, onClick = { model.command("readDeviceInfo") }) { Text(stringResource(R.string.read_device)) }
                    TextButton(enabled = !state.running && !model.busy && state.bluetoothOn, onClick = { model.command("getHapticIntensity") }) { Text(stringResource(R.string.read_strength)) }
                    if (model.result.isNotBlank()) Text(model.result)
                }
            }
        }
        item { Text(stringResource(R.string.delivery_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AccessRow(title: String, description: String, ready: Boolean, action: Int, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (ready) Icon(Icons.Default.CheckCircle, stringResource(R.string.ready), tint = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onAction) { Text(stringResource(action)) }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalBadge(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AppsScreen(model: SignalsModel, draft: Map<String, Int>, query: String, modifier: Modifier, onQuery: (String) -> Unit, onApp: (String) -> Unit, onDiscard: () -> Unit) {
    val apps = model.apps
    val filtered = apps?.filter { it.label.contains(query, true) }
    LazyColumn(modifier.fillMaxHeight(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.selected_count, draft.size), style = MaterialTheme.typography.titleMedium)
            if (draft != model.state.rules) {
                Text(stringResource(R.string.unsaved_apps), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = onQuery, label = { Text(stringResource(R.string.search_apps)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } })
        }
        if (apps == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.loading_apps)) }
        else if (filtered.isNullOrEmpty()) item { Text(stringResource(R.string.no_search_results)) }
        else items(filtered, key = { it.packageName }) { app ->
            OutlinedCard(onClick = { onApp(app.packageName) }, modifier = Modifier.fillMaxWidth().testTag("app-${app.packageName}")) {
                ListItem(headlineContent = { Text(app.label) },
                    supportingContent = { Text(draft[app.packageName]?.let { stringResource(R.string.signal_name, Rules.profileName(it)) } ?: stringResource(R.string.off)) },
                    trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) })
            }
        }
    }
}

@Composable
private fun PatternsScreen(model: SignalsModel, modifier: Modifier, onPreview: (Int) -> Unit, onEdit: (Int) -> Unit) {
    LazyColumn(modifier.fillMaxHeight(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text(stringResource(R.string.pattern_intro), style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.pattern_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (model.state.running) item { Text(stringResource(R.string.stop_to_preview), style = MaterialTheme.typography.bodyMedium) }
        items((1..3).toList()) { profile ->
            val pattern = model.state.profiles[profile - 1]
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SignalBadge(Rules.profileName(profile))
                        Column { Text(stringResource(R.string.signal_name, Rules.profileName(profile)), style = MaterialTheme.typography.titleLarge); Text(groupsText(pattern.groups)) }
                    }
                    GroupDiagram(pattern)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(enabled = !model.state.running && !model.busy && model.state.bluetoothOn, onClick = { onPreview(profile) }) { Text(stringResource(R.string.preview)) }
                        OutlinedButton(onClick = { onEdit(profile) }) { Text(stringResource(R.string.edit)) }
                    }
                }
            }
        }
        if (model.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::stop) { Text(stringResource(R.string.stop_preview)) } }
        if (model.result.isNotBlank()) item { Text(model.result) }
    }
}

@Composable
private fun GroupDiagram(pattern: SignalProfile) {
    val color = MaterialTheme.colorScheme.primary
    val description = groupsText(pattern.groups) + if (pattern.groups > 1) ", " + stringResource(R.string.gap_description, String.format(Locale.getDefault(), "%.1f", pattern.gap / 1000f)) else ""
    Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = description }) {
        val unit = 7.dp.toPx(); val separation = 30.dp.toPx()
        repeat(pattern.groups) { group -> repeat(3) { bar ->
            val height = if (bar == 1) 28.dp.toPx() else 16.dp.toPx()
            drawRoundRect(color, Offset(group * (unit * 5 + separation) + bar * unit * 1.7f, (size.height - height) / 2), Size(unit, height), CornerRadius(unit))
        } }
    }
}

@Composable
private fun PatternEditor(profile: Int, initial: SignalProfile, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var groups by rememberSaveable(profile) { mutableIntStateOf(initial.groups) }
    var gap by rememberSaveable(profile) { mutableIntStateOf(initial.gap) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.edit_signal, Rules.profileName(profile)), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.number_groups), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 1..3) FilterChip(selected = groups == n, onClick = { groups = n }, label = { Text(groupsText(n)) }) }
            if (groups > 1) {
                Text(stringResource(R.string.gap_between), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (millis in listOf(1200, 1800, 2400, 3000)) FilterChip(selected = gap == millis, onClick = { gap = millis }, label = { Text(stringResource(R.string.gap_value, String.format(Locale.getDefault(), "%.1f", millis / 1000f))) }) }
            }
            GroupDiagram(SignalProfile(groups, gap))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(groups, gap) }) { Text(stringResource(R.string.save)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
