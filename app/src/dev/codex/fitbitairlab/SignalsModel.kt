package dev.codex.fitbitairlab

import android.Manifest
import android.app.Application
import android.os.Build
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SignalProfile(val groups: Int, val gap: Int)
internal data class AppChoice(val packageName: String, val label: String)
internal data class SignalsState(
    val running: Boolean = false,
    val bluetoothAllowed: Boolean = false,
    val bluetoothOn: Boolean = false,
    val listenerAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
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
    init { refresh() }
    internal fun label(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    internal fun refresh() {
        val bluetooth = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        state = SignalsState(
            running = RelayService.instance != null,
            bluetoothAllowed = bluetooth,
            bluetoothOn = bluetooth && runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false),
            listenerAllowed = RelayService.accessGranted(ctx),
            notificationsAllowed = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            rules = Rules.all(ctx),
            profiles = (1..3).map { SignalProfile(Rules.groups(ctx, it), Rules.gap(ctx, it)) },
            lastStatus = Rules.prefs(ctx).getString("last_state", "") ?: "",
        )
    }
    internal fun loadApps() {
        if (apps != null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }
                        .map { AppChoice(it, label(it)) }.sortedBy { it.label.lowercase() }
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
                AirEngine.call(ctx, "cancelOperation") { }
            }
        }
        refresh()
    }
    internal fun command(method: String, profile: Int? = null) {
        if (busy || state.running || !state.bluetoothAllowed || !state.bluetoothOn) return
        busy = true; result = ctx.getString(R.string.working)
        val args = profile?.let { state.profiles[it - 1].let { p -> mapOf("groups" to p.groups, "gapMillis" to p.gap) } }
        AirEngine.call(ctx, method, args) { value ->
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
}
