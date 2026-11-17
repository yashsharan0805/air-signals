package dev.codex.fitbitairlab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import java.time.ZoneId
import java.util.UUID

internal class AndroidTimeClock(private val context: Context) : TimeClock {
    override val wall get() = System.currentTimeMillis()
    override val elapsed get() = SystemClock.elapsedRealtime()
    override val boot get() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    override val zone get() = ZoneId.systemDefault()
}

internal class AndroidTimeStore(context: Context) : TimeStateStore {
    private val prefs = context.getSharedPreferences("time_alerts", Context.MODE_PRIVATE)
    override fun read() = TimeStateJson.decode(prefs.getString("state", "{}") ?: "{}")
    override fun write(state: TimeAlertState) { check(prefs.edit().putString("state", TimeStateJson.encode(state)).commit()) { "Could not save the schedule." } }
}

internal class AndroidAlarmBackend(private val context: Context) : TimeAlarmBackend {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    override fun canSchedule() = alarms.canScheduleExactAlarms()
    private fun operation(key: String, token: String = "") = PendingIntent.getBroadcast(context, 0,
        Intent(context, TimeAlertReceiver::class.java).setAction(TimeAlerts.FIRE).setData(Uri.parse("airsignals://scheduled/$key")).putExtra("key", key).putExtra("token", token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun schedule(key: String, token: String, deadline: TimeDeadline, elapsedBased: Boolean) {
        val pending = operation(key, token)
        if (elapsedBased) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline.elapsed, pending)
        else alarms.setAlarmClock(AlarmManager.AlarmClockInfo(deadline.wall, TimeNotifications.open(context)), pending)
    }
    override fun cancel(key: String) { alarms.cancel(operation(key)) }
}

internal object TimeAlerts {
    const val FIRE = "dev.codex.fitbitairlab.TIME_FIRE"
    const val STOP = "dev.codex.fitbitairlab.TIME_STOP"
    const val SNOOZE = "dev.codex.fitbitairlab.TIME_SNOOZE"
    fun manager(context: Context) = TimeAlertManager(AndroidTimeStore(context), AndroidTimeClock(context), AndroidAlarmBackend(context))
    fun notificationsAllowed(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun bluetoothAllowed(context: Context) = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun recover(context: Context) {
        runCatching { manager(context).restore(); sync(context) }
            .onFailure { manager(context).status(it.message ?: "Could not restore saved schedules.") }
    }
    fun snapshot(context: Context): TimeAlertState {
        val manager = manager(context)
        val access = AndroidAlarmBackend(context).canSchedule()
        if (!access) manager.permissionRevoked()
        else if (!manager.snapshot().permissionWasGranted) manager.restore()
        return manager.snapshot()
    }
    fun sync(context: Context, start: Boolean = false) {
        val state = manager(context).snapshot()
        TimeNotifications.sync(context, state)
        val service = TimeAlertService.instance?.takeIf { !it.closing }
        if (service != null) service.refresh()
        else if (start && state.active.isNotEmpty()) {
            try { context.startForegroundService(Intent(context, TimeAlertService::class.java)) }
            catch (_: Exception) { manager(context).status("Could not start the alarm connection. Open Air Signals; the phone alert remains available.") }
        }
    }
}

/** Scheduled broadcasts carry no notification content and never need notification-listener access. */
class TimeAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = TimeAlerts.manager(context)
        try {
            when (intent.action) {
                TimeAlerts.FIRE -> {
                    val ring = manager.fire(intent.getStringExtra("key") ?: return, intent.getStringExtra("token") ?: return)
                    if (ring != null) {
                        if (!TimeAlerts.notificationsAllowed(context)) {
                            manager.stop(ring.token)
                            manager.status("An alarm or timer expired, but phone notifications are blocked. Open Settings to restore alerts.")
                        } else TimeAlerts.sync(context, start = true)
                    }
                }
                TimeAlerts.STOP -> { manager.stop(intent.getStringExtra("token")); TimeAlerts.sync(context) }
                TimeAlerts.SNOOZE -> { manager.snooze(intent.getStringExtra("token") ?: return); TimeAlerts.sync(context) }
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                    manager.restore(clockChanged = intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED), reboot = intent.action == Intent.ACTION_BOOT_COMPLETED)
                    TimeAlerts.sync(context)
                }
            }
        } catch (e: Exception) {
            manager.status(e.message ?: "Could not update the timer or alarm.")
            TimeNotifications.sync(context, manager.snapshot())
        }
    }
}

internal object TimeNotifications {
    const val CHANNEL = "air_signals_time_alerts"
    private const val STATUS_CHANNEL = "air_signals_time_status"
    const val FOREGROUND_ID = 9000
    private const val TAG = "scheduled-alert"
    fun open(context: Context) = PendingIntent.getActivity(context, 20, Intent(context, LabActivity::class.java).putExtra("page", "timers"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun action(context: Context, action: String, token: String?) = PendingIntent.getBroadcast(context, 0,
        Intent(context, TimeAlertReceiver::class.java).setAction(action).setData(Uri.parse("airsignals://ring/${action.substringAfterLast('.')}/${token ?: "all"}" )).apply { token?.let { putExtra("token", it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Expiry alerts, with Stop and Snooze controls. Phone alarm sound is controlled in Air Signals."
            setSound(null, null); enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Timer and alarm connection", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
    }
    fun notification(context: Context, state: TimeAlertState, ring: RingingAlert? = null): Notification {
        channel(context)
        val title = ring?.title ?: state.active.joinToString { it.title }.ifBlank { "Timers and alarms" }
        val builder = Notification.Builder(context, if (ring == null) STATUS_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_signal).setContentTitle(title)
            .setContentText("Open Air Signals, stop, or snooze for five minutes.").setContentIntent(open(context))
            .setCategory(if (ring == null) Notification.CATEGORY_SERVICE else Notification.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (ring == null) "Stop all" else "Stop", action(context, TimeAlerts.STOP, ring?.token)).build())
        // Android also removes the per-alert notification if our process/service
        // exits before it can perform its normal two-minute cleanup.
        ring?.let { builder.setTimeoutAfter(it.expires.remaining(AndroidTimeClock(context)).coerceAtLeast(1)) }
        (ring ?: state.active.firstOrNull())?.let { builder.addAction(Notification.Action.Builder(null, "Snooze 5 min", action(context, TimeAlerts.SNOOZE, it.token)).build()) }
        return builder.build()
    }
    fun sync(context: Context, state: TimeAlertState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tokens = state.active.map { it.token }.toSet()
        manager.activeNotifications.filter { it.tag?.startsWith("$TAG/") == true && it.tag?.removePrefix("$TAG/") !in tokens }
            .forEach { manager.cancel(it.tag, 1) }
        if (TimeAlerts.notificationsAllowed(context)) state.active.forEach { manager.notify("$TAG/${it.token}", 1, notification(context, state, it)) }
    }
}

/** Bounded ringing session. Phone sound and wrist delivery are independent fallbacks. */
class TimeAlertService : Service() {
    companion object { @Volatile var instance: TimeAlertService? = null }
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var lock: PowerManager.WakeLock? = null
    private var pending = false
    private var closed = false
    internal val closing get() = closed
    private var wristPaused = false
    private var owner = "scheduled/${UUID.randomUUID()}"
    private var lastWristToken: String? = null
    private var lastStartId = 0
    private val tick = object : Runnable { override fun run() { refresh(); if (!closed) handler.postDelayed(this, 1000) } }
    private val send = Runnable { sendWristSignal() }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (closed) {
            closed = false; pending = false; wristPaused = false; sendingToken = null
            owner = "scheduled/${UUID.randomUUID()}"; lastWristToken = null; instance = this
        }
        val state = TimeAlerts.manager(this).snapshot()
        if (state.active.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val bluetooth = TimeAlerts.bluetoothAllowed(this)
        val types = (if (bluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
            (if (state.phoneSound) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (types == 0) {
            TimeAlerts.manager(this).status("Nearby devices access is required for wrist alerts. The phone notification remains available.")
            stopSelf(); return START_NOT_STICKY
        }
        try { startForeground(TimeNotifications.FOREGROUND_ID, TimeNotifications.notification(this, state), types) }
        catch (_: Exception) {
            TimeAlerts.manager(this).status("Could not start the alarm service. Open Air Signals to review the phone alert.")
            stopSelf(); return START_NOT_STICKY
        }
        lock = (lock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSignals:time-alert").apply { setReferenceCounted(false) }).apply { acquire(150000) }
        handler.removeCallbacks(tick); handler.post(tick)
        if (!pending) { handler.removeCallbacks(send); handler.post(send) }
        return START_NOT_STICKY
    }
    fun refresh() {
        if (closed) return
        val manager = TimeAlerts.manager(this)
        val state = manager.snapshot()
        TimeNotifications.sync(this, state)
        if (state.active.isEmpty()) { shutdown(); return }
        val types = (if (TimeAlerts.bluetoothAllowed(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
            (if (state.phoneSound) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (types == 0) { shutdown(); return }
        try { startForeground(TimeNotifications.FOREGROUND_ID, TimeNotifications.notification(this, state), types) }
        catch (_: Exception) { manager.status("Alarm access changed. Use the phone notification to stop this alert."); shutdown(); return }
        getSystemService(NotificationManager::class.java).notify(TimeNotifications.FOREGROUND_ID, TimeNotifications.notification(this, state))
        if (state.phoneSound && ringtone == null) {
            runCatching {
                ringtone = (RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) ?: error("No alarm sound is configured")).apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true; play()
                }
            }.onFailure { manager.status("The phone alarm sound could not start. Check alarm volume; the notification remains available.") }
        } else if (!state.phoneSound) { ringtone?.stop(); ringtone = null }
        if (pending && state.active.none { it.token == sendingToken }) AirEngine.cancel(owner)
    }
    private var sendingToken: String? = null
    private fun sendWristSignal() {
        if (closed || pending || wristPaused) return
        val manager = TimeAlerts.manager(this)
        val active = manager.snapshot().active
        if (active.isEmpty()) return
        val ring = active[(active.indexOfFirst { it.token == lastWristToken } + 1) % active.size]
        if (!TimeAlerts.bluetoothAllowed(this)) { manager.status("Nearby devices access is unavailable; use the phone alert."); return }
        pending = true; sendingToken = ring.token
        val sessionOwner = owner
        AirEngine.call(this, "pattern", mapOf("groups" to Rules.groups(this, ring.profile), "gapMillis" to Rules.gap(this, ring.profile)), owner = sessionOwner, priority = 10,
            allowed = { !closed && owner == sessionOwner && TimeAlerts.manager(this).snapshot().active.any { it.token == ring.token } }) { result ->
            val cleanup = result["cleanup"] as? Map<*, *>
            val steps = cleanup?.get("steps") as? Map<*, *>
            val failed = result["status"] != "skipped" && (result["status"] != "success" || steps?.get("channelHandoff") != "reset_observed")
            if (failed) manager.status("Wrist delivery was not verified. Use the phone alert and check your Air and vibration strength.")
            if (owner != sessionOwner || closed) return@call
            pending = false; sendingToken = null
            if (failed) {
                wristPaused = true
            } else if (!wristPaused) {
                lastWristToken = ring.token
                handler.postDelayed(send, if (result["status"] == "skipped") 0 else 15000)
            }
        }
    }
    private fun shutdown() {
        closed = true; handler.removeCallbacksAndMessages(null)
        AirEngine.cancel(owner)
        ringtone?.stop(); ringtone = null
        if (lock?.isHeld == true) lock?.release()
        lock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStartId)
    }
    override fun onDestroy() { if (!closed) shutdown(); if (instance === this) instance = null; super.onDestroy() }
}
