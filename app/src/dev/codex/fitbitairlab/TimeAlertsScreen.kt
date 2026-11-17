@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codex.fitbitairlab

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.Calendar

internal fun countdownText(millis: Long): String {
    val seconds = ((millis.coerceAtLeast(0) + 999) / 1000)
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
private fun localDeadline(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
private fun alarmClockText(context: Context, hour: Int, minute: Int): String = DateFormat.getTimeFormat(context).format(Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
}.time)

@Composable
internal fun TimeAlertsScreen(model: SignalsModel, modifier: Modifier, openExact: () -> Unit, allowNotifications: () -> Unit, allowBluetooth: () -> Unit) {
    val context = LocalContext.current
    val state = model.timeAlerts
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxHeight().testTag("time-alert-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(stringResource(R.string.time_intro), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.time_help), style = MaterialTheme.typography.bodyMedium)
        }
        if (!model.exactAlarmAllowed || !model.timeNotificationsAllowed || !model.state.bluetoothAllowed) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.time_setup), style = MaterialTheme.typography.titleMedium)
                    if (!model.exactAlarmAllowed) {
                        Text(stringResource(R.string.exact_alarm_help))
                        FilledTonalButton(onClick = openExact, Modifier.testTag("allow-exact-alarms")) { Text(stringResource(R.string.exact_alarm_access)) }
                    }
                    if (!model.timeNotificationsAllowed) {
                        Text(stringResource(R.string.time_notifications_help))
                        FilledTonalButton(onClick = allowNotifications) { Text(stringResource(R.string.time_notifications)) }
                    }
                    if (!model.state.bluetoothAllowed) {
                        Text(stringResource(R.string.time_wrist_access))
                        TextButton(onClick = allowBluetooth) { Text(stringResource(R.string.bluetooth)) }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().testTag("phone-alarm-sound")
                    .toggleable(state.phoneSound, role = Role.Switch, onValueChange = { enabled -> model.timeAction { it.setPhoneSound(enabled) } })
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.phone_alarm_sound), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_alarm_sound_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(state.phoneSound, onCheckedChange = null)
                }
            }
        }
        if (state.lastStatus.isNotBlank()) item { Text(state.lastStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (state.active.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.time_ringing), style = MaterialTheme.typography.titleLarge)
                state.active.forEach { ring ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ring.title, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { model.timeAction { it.stop(ring.token) } }) { Text(stringResource(R.string.time_stop)) }
                                TextButton(enabled = model.timeReady, onClick = { model.timeAction { it.snooze(ring.token) } }) { Text(stringResource(R.string.time_snooze)) }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.time_ring_limit), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            PrimaryTabRow(tab) {
                Tab(tab == 0, onClick = { tab = 0 }, modifier = Modifier.testTag("timer-tab"), text = { Text(stringResource(R.string.timers)) })
                Tab(tab == 1, onClick = { tab = 1 }, modifier = Modifier.testTag("alarm-tab"), text = { Text(stringResource(R.string.alarms)) })
            }
        }
        if (tab == 0) {
            state.timer?.let { timer -> item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when (timer.state) { "running" -> R.string.timer_running; "paused" -> R.string.timer_paused; "finished" -> R.string.timer_finished; else -> R.string.timer_cancelled }), style = MaterialTheme.typography.titleMedium)
                        Text(countdownText(model.timerRemaining), Modifier.testTag("countdown"), style = MaterialTheme.typography.displaySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (timer.state) {
                                "running" -> Button(onClick = { model.timeAction { it.pauseTimer() } }, enabled = model.timerRemaining > 0) { Text(stringResource(R.string.timer_pause)) }
                                "paused" -> Button(onClick = { model.timeAction { it.resumeTimer() } }, enabled = model.timeReady) { Text(stringResource(R.string.timer_resume)) }
                            }
                            if (timer.state in setOf("running", "paused")) TextButton(onClick = { model.timeAction { it.cancelTimer() } }) { Text(stringResource(R.string.timer_cancel)) }
                            TextButton(onClick = { model.startTimer(timer.duration, timer.profile) }, enabled = model.timeReady) { Text(stringResource(R.string.timer_restart)) }
                        }
                    }
                }
            } }
            item { TimerForm(model) }
        } else {
            item { Button(onClick = { editing = "new" }, enabled = model.timeReady, modifier = Modifier.testTag("add-alarm")) { Text(stringResource(R.string.alarm_add)) } }
            if (state.alarms.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.alarm_none), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.alarm_none_help))
                }
            }
            state.alarms.sortedWith(compareBy<WakeAlarm> { !it.enabled }.thenBy { it.deadline?.wall ?: Long.MAX_VALUE }).forEach { alarm -> item(key = alarm.id) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(alarmClockText(context, alarm.hour, alarm.minute), style = MaterialTheme.typography.headlineMedium)
                                if (alarm.label.isNotBlank()) Text(alarm.label, style = MaterialTheme.typography.titleMedium)
                                Text(if (alarm.days.isEmpty()) stringResource(R.string.alarm_once) else alarm.days.sorted().joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) })
                            }
                            Switch(alarm.enabled, onCheckedChange = { enabled -> model.timeAction { it.setAlarmEnabled(alarm.id, enabled) } }, enabled = !alarm.enabled && model.timeReady || alarm.enabled,
                                modifier = Modifier.testTag("alarm-toggle-${alarm.id}"))
                        }
                        Text(alarm.deadline?.let { stringResource(if (alarm.snoozed) R.string.alarm_snoozed else R.string.alarm_next, localDeadline(it.wall)) } ?: stringResource(R.string.alarm_off), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = alarm.id }, enabled = model.timeReady) { Text(stringResource(R.string.edit)) }
                            if (alarm.enabled) TextButton(onClick = { model.timeAction { it.skipNext(alarm.id) } }, enabled = model.timeReady) { Text(stringResource(R.string.alarm_skip)) }
                            TextButton(onClick = { model.timeAction { it.deleteAlarm(alarm.id) } }) { Text(stringResource(R.string.alarm_delete)) }
                        }
                    }
                }
            } }
        }
        item {
            Text(stringResource(R.string.time_gestures), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.time_gestures_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.time_clock_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { id ->
        AlarmEditor(state.alarms.firstOrNull { it.id == id }, onDismiss = { editing = null }, onSave = { if (model.saveAlarm(it)) editing = null })
    }
}

@Composable
private fun TimerForm(model: SignalsModel) {
    var hours by rememberSaveable { mutableStateOf("0") }
    var minutes by rememberSaveable { mutableStateOf("5") }
    var seconds by rememberSaveable { mutableStateOf("0") }
    var profile by rememberSaveable { mutableIntStateOf(1) }
    val h = hours.toLongOrNull(); val m = minutes.toLongOrNull(); val s = seconds.toLongOrNull()
    val duration = if (h != null && m != null && s != null && h in 0..168 && m in 0..59 && s in 0..59) (h * 3600 + m * 60 + s) * 1000 else 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.timer_duration), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 30).forEach { value -> FilterChip(duration == value * 60000L, onClick = { hours = "0"; minutes = value.toString(); seconds = "0" }, label = { Text("$value min") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeNumber(hours, { hours = it }, R.string.timer_hours, "timer-hours", Modifier.weight(1f), 3)
            TimeNumber(minutes, { minutes = it }, R.string.timer_minutes, "timer-minutes", Modifier.weight(1f))
            TimeNumber(seconds, { seconds = it }, R.string.timer_seconds, "timer-seconds", Modifier.weight(1f))
        }
        if (duration !in 1000L..604800000L) Text(stringResource(R.string.timer_invalid), color = MaterialTheme.colorScheme.error)
        ProfilePicker(profile) { profile = it }
        if (model.timeAlerts.timer?.state in setOf("running", "paused")) Text(stringResource(R.string.timer_replace), style = MaterialTheme.typography.bodySmall)
        Button(enabled = model.timeReady && duration in 1000L..604800000L, onClick = { model.startTimer(duration, profile) }, modifier = Modifier.fillMaxWidth().testTag("start-timer")) { Text(stringResource(R.string.timer_start)) }
    }
}

@Composable
private fun TimeNumber(value: String, change: (String) -> Unit, label: Int, tag: String, modifier: Modifier, maxLength: Int = 2) {
    OutlinedTextField(value, onValueChange = { if (it.length <= maxLength && it.all(Char::isDigit)) change(it) }, label = { Text(stringResource(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier.testTag(tag))
}

@Composable
private fun ProfilePicker(profile: Int, select: (Int) -> Unit) {
    Text(stringResource(R.string.time_signal), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..3).forEach { choice -> FilterChip(profile == choice, onClick = { select(choice) }, label = { Text(stringResource(R.string.signal_name, Rules.profileName(choice))) }) }
    }
}

@Composable
private fun AlarmEditor(alarm: WakeAlarm?, onDismiss: () -> Unit, onSave: (WakeAlarm) -> Unit) {
    val context = LocalContext.current
    var label by rememberSaveable { mutableStateOf(alarm?.label ?: "") }
    var hour by rememberSaveable { mutableIntStateOf(alarm?.hour ?: 7) }
    var minute by rememberSaveable { mutableIntStateOf(alarm?.minute ?: 0) }
    var pickedTime by rememberSaveable { mutableStateOf(alarm != null) }
    var pickTime by rememberSaveable { mutableStateOf(alarm == null) }
    var days by rememberSaveable { mutableStateOf(alarm?.days?.sorted() ?: emptyList()) }
    var profile by rememberSaveable { mutableIntStateOf(alarm?.profile ?: 1) }
    if (pickTime) {
        AlarmClockDialog(hour, minute,
            onDismiss = { if (pickedTime) pickTime = false else onDismiss() },
            onConfirm = { h, m -> hour = h; minute = m; pickedTime = true; pickTime = false })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (alarm == null) R.string.alarm_add else R.string.alarm_edit)) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, onValueChange = { label = it.take(60) }, label = { Text(stringResource(R.string.alarm_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("alarm-label"))
                TextButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth().testTag("edit-alarm-time")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(alarmClockText(context, hour, minute), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.alarm_change_time), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(stringResource(R.string.alarm_repeat), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { day -> FilterChip(day in days, onClick = { days = if (day in days) days - day else days + day }, label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())) }) }
                }
                if (days.isEmpty()) Text(stringResource(R.string.alarm_once))
                ProfilePicker(profile) { profile = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave((alarm ?: WakeAlarm(hour = 7, minute = 0)).copy(label = label, hour = hour, minute = minute, days = days.toSet(), profile = profile, enabled = true)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun AlarmClockDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    var keyboard by rememberSaveable { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(260.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.alarm_select_time), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (keyboard) TimeInput(state, modifier = Modifier.testTag("alarm-time-input"))
                    else TimePicker(state, modifier = Modifier.testTag("alarm-clock-picker"))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { keyboard = !keyboard }, modifier = Modifier.testTag("time-picker-mode")) { Text(stringResource(if (keyboard) R.string.alarm_clock_mode else R.string.alarm_keyboard_mode)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }, modifier = Modifier.testTag("confirm-alarm-time")) { Text(stringResource(R.string.alarm_set_time)) }
                }
            }
        }
    }
}
