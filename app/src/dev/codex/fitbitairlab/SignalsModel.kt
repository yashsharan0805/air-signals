package dev.codex.fitbitairlab

import android.Manifest
import android.app.Application
import android.os.Build
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class SignalProfile(val groups: Int, val gap: Int)
internal data class AppChoice(val packageName: String, val label: String, val icon: ImageBitmap? = null)
internal data class SignalsState(
    val running: Boolean = false,
    val bluetoothAllowed: Boolean = false,
    val bluetoothOn: Boolean = false,
    val listenerAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val muteWhileScreenOn: Boolean = false,
    val rules: Map<String, Int> = emptyMap(),
    val profiles: List<SignalProfile> = emptyList(),
    val lastStatus: String = "",
) {
    val ready get() = bluetoothAllowed && bluetoothOn && listenerAllowed && notificationsAllowed && rules.isNotEmpty()
}

class SignalsModel(application: Application) : AndroidViewModel(application) {
    private val ctx get() = getApplication<Application>()
    internal var state by mutableStateOf(SignalsState()); private set
    internal var apps by mutableStateOf<List<AppChoice>?>(null); private set
    internal var busy by mutableStateOf(false); private set
    internal var result by mutableStateOf(""); private set
    internal var notice by mutableStateOf<String?>(null); private set
    internal var timeAlerts by mutableStateOf(TimeAlertState()); private set
    internal var exactAlarmAllowed by mutableStateOf(false); private set
    internal var timeNotificationsAllowed by mutableStateOf(false); private set
    internal var timeTick by mutableLongStateOf(0L); private set
    internal val timeReady get() = exactAlarmAllowed && timeNotificationsAllowed && (state.bluetoothAllowed || timeAlerts.phoneSound)
    internal val timerRemaining: Long get() {
        timeTick // Subscribe the countdown display to the activity's one-second refresh.
        val timer = timeAlerts.timer ?: return 0
        return if (timer.state == "running") timer.deadline?.remaining(AndroidTimeClock(ctx))?.coerceAtLeast(0) ?: 0 else timer.remaining
    }
    init { TimeAlerts.recover(ctx); refresh() }
    internal fun label(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    private fun appIcon(pkg: String): ImageBitmap? = runCatching {
        val manager = ctx.packageManager
        val drawable = runCatching { manager.getApplicationIcon(pkg) }.getOrElse { manager.defaultActivityIcon }
        val size = (40 * ctx.resources.displayMetrics.density).roundToInt().coerceIn(40, 144)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = size.toFloat() / maxOf(width, height)
        val drawnWidth = (width * scale).roundToInt()
        val drawnHeight = (height * scale).roundToInt()
        val left = (size - drawnWidth) / 2; val top = (size - drawnHeight) / 2
        val previous = Rect(drawable.bounds)
        try {
            drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight)
            drawable.draw(Canvas(bitmap))
        } finally { drawable.bounds = previous }
        bitmap.asImageBitmap()
    }.getOrNull()
    internal fun refresh() {
        val bluetooth = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        state = SignalsState(
            running = RelayService.instance != null,
            bluetoothAllowed = bluetooth,
            bluetoothOn = bluetooth && runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false),
            listenerAllowed = RelayService.accessGranted(ctx),
            notificationsAllowed = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            muteWhileScreenOn = Rules.muteWhileScreenOn(ctx),
            rules = Rules.all(ctx),
            profiles = (1..3).map { SignalProfile(Rules.groups(ctx, it), Rules.gap(ctx, it)) },
            lastStatus = Rules.prefs(ctx).getString("last_state", "") ?: "",
        )
        exactAlarmAllowed = AndroidAlarmBackend(ctx).canSchedule()
        timeNotificationsAllowed = TimeAlerts.notificationsAllowed(ctx)
        timeAlerts = TimeAlerts.snapshot(ctx)
        timeTick = android.os.SystemClock.elapsedRealtime()
    }
    internal fun loadApps() {
        if (apps != null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val launchers = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }
                    // Keep saved assignments visible even if an app lost its launcher or was uninstalled.
                    (launchers + Rules.all(ctx).keys).distinct()
                        .map { AppChoice(it, label(it), appIcon(it)) }.sortedBy { it.label.lowercase() }
                }
            }
            loaded.onSuccess { apps = it }.onFailure { message(R.string.app_load_failed) }
        }
    }
    internal fun message(id: Int) { notice = ctx.getString(id) }
    internal fun consumeNotice(value: String) { if (notice == value) notice = null }
    internal fun saveApps(choices: Map<String, Int>) {
        if (choices.size > 5) { message(R.string.app_limit); return }
        Rules.save(ctx, choices); refresh(); message(R.string.app_saved)
    }
    internal fun saveProfile(profile: Int, groups: Int, gap: Int) {
        Rules.prefs(ctx).edit().putInt("groups_$profile", groups).putInt("gap_$profile", gap).apply()
        refresh(); message(R.string.pattern_saved)
    }
    internal fun setMuteWhileScreenOn(enabled: Boolean) {
        Rules.prefs(ctx).edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, enabled).apply()
        refresh()
    }
    internal fun startAlerts() {
        refresh()
        if (!state.ready || busy) return
        runCatching { ctx.startForegroundService(Intent(ctx, RelayService::class.java)) }
            .onFailure { message(R.string.start_failed) }
    }
    internal fun stop() {
        RelayService.instance?.requestStop(ctx.getString(R.string.stop_alerts)) ?: run {
            if (busy) {
                result = ctx.getString(R.string.operation_stopped)
                AirEngine.cancel("manual")
            }
        }
        refresh()
    }
    internal fun command(method: String, profile: Int? = null) {
        if (busy || state.running || timeAlerts.active.isNotEmpty() || !state.bluetoothAllowed || !state.bluetoothOn) return
        busy = true; result = ctx.getString(R.string.working)
        val args = profile?.let { state.profiles[it - 1].let { p -> mapOf("groups" to p.groups, "gapMillis" to p.gap) } }
        AirEngine.call(ctx, method, args, allowed = { TimeAlerts.manager(ctx).snapshot().active.isEmpty() }) { value ->
            busy = false
            val data = value["result"] as? Map<*, *>
            result = when {
                value["status"] != "success" -> ctx.getString(R.string.operation_failed, value["message"] ?: value["code"] ?: "")
                method == "readDeviceInfo" -> ctx.getString(R.string.firmware_result, data?.get("name") ?: "Fitbit Air", data?.get("firmware") ?: "—")
                method == "getHapticIntensity" -> ctx.getString(R.string.strength_result, ctx.getString(if (data?.get("intensity") == 2) R.string.low else R.string.high))
                else -> ctx.getString(R.string.preview_result, data?.get("verified").toString())
            }
            notice = result
            refresh()
        }
    }
    internal fun timeAction(action: (TimeAlertManager) -> Unit): Boolean {
        val result = runCatching { action(TimeAlerts.manager(ctx)); TimeAlerts.sync(ctx) }
            .onFailure { notice = it.message ?: ctx.getString(R.string.time_action_failed) }
        refresh()
        return result.isSuccess
    }
    internal fun startTimer(duration: Long, profile: Int) {
        if (!timeReady) { message(R.string.time_setup_needed); return }
        timeAction { it.startTimer(duration, profile) }
    }
    internal fun saveAlarm(alarm: WakeAlarm): Boolean {
        if (!timeReady) { message(R.string.time_setup_needed); return false }
        return timeAction { it.saveAlarm(alarm) }
    }
}
