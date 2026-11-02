package dev.codex.fitbitairlab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AirNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val relay = RelayService.instance ?: return
        val source = sbn.packageName
        if (source == packageName) return
        val profile = Rules.all(this)[source] ?: return
        val age = System.currentTimeMillis() - sbn.postTime
        if (age > 45000 || age < -60000) return
        val flags = sbn.notification.flags
        if ((flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY)) != 0) return
        // Never inspect extras, title, text, sender, actions, contact data or notification key.
        val item = PendingAlert(source, Rules.groups(this, profile), Rules.gap(this, profile), SystemClock.elapsedRealtime(), "$source/${sbn.id}/${sbn.postTime}")
        relay.enqueue(item)
    }
    override fun onListenerDisconnected() { RelayService.instance?.requestStop("Notification access disconnected. Open the app to restart alerts.") }
}

class RelayService : Service() {
    companion object {
        @Volatile var instance: RelayService? = null
        const val STOP = "dev.codex.fitbitairlab.STOP"
        const val CHANNEL = "air_signals_status"
        const val NOTIFICATION_ID = 8901
        fun accessGranted(ctx: Context): Boolean = ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(ctx, AirNotificationListener::class.java))
    }
    private val handler = Handler(Looper.getMainLooper())
    private val queue = AlertQueue()
    private var accepting = false
    private var busy = false
    private var lastCompleted = Long.MIN_VALUE / 2
    private var lock: PowerManager.WakeLock? = null
    private val drainTask = Runnable { drain() }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { requestStop("Alerts stopped"); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Alert connection", NotificationManager.IMPORTANCE_LOW).apply { enableVibration(false); setSound(null, null) })
        try { startForeground(NOTIFICATION_ID, notification("Starting…")) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || !accessGranted(this) || Rules.all(this).isEmpty()) {
            requestStop("Choose apps and enable Bluetooth / notification access first.")
            return START_NOT_STICKY
        }
        accepting = true
        state("Listening for selected apps")
        // Each delivery owns a short-lived GATT client. Do not retain an idle
        // subscription to the channel Google Health also uses.
        AirEngine.call(this, "keepLinkWarm", mapOf("enabled" to false)) { }
        return START_NOT_STICKY
    }
    fun enqueue(item: PendingAlert) {
        handler.post {
            if (!accepting) return@post
            val outcome = queue.offer(item, SystemClock.elapsedRealtime())
            LabBridge.record(this, mapOf("event" to "notification", "source" to item.source, "outcome" to outcome, "groups" to item.groups))
            if (outcome in setOf("queued", "coalesced")) schedule()
        }
    }
    private fun schedule() {
        // One bounded debounce window merges bursts without postponing forever.
        if (accepting && !busy && !handler.hasCallbacks(drainTask)) handler.postDelayed(drainTask, (5000 - (SystemClock.elapsedRealtime() - lastCompleted)).coerceAtLeast(1000))
    }
    private fun drain() {
        if (!accepting || busy) return
        val item = queue.take(SystemClock.elapsedRealtime()) ?: return
        busy = true
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSignals:delivery").apply { acquire(60000) }
        state("Sending ${item.groups} group(s)")
        AirEngine.call(this, "pattern", mapOf("groups" to item.groups, "gapMillis" to item.gapMillis)) { result ->
            if (lock?.isHeld == true) lock?.release()
            lock = null; busy = false; lastCompleted = SystemClock.elapsedRealtime()
            LabBridge.record(this, result + mapOf("event" to "delivery", "source" to item.source, "queueAgeMillis" to (lastCompleted - item.received)))
            if (result["status"] != "success") {
                requestStop("Delivery failed. Alerts paused; check your Air and vibration strength before restarting.")
            } else {
                val cleanup = result["cleanup"] as? Map<*, *>
                val steps = cleanup?.get("steps") as? Map<*, *>
                if (steps?.get("channelHandoff") != "reset_observed") {
                    requestStop("Air connection handoff was not verified. Alerts paused; check Google Health before restarting.")
                } else if (!accepting) finishStop() else { state("Listening for selected apps"); schedule() }
            }
        }
    }
    fun requestStop(reason: String) {
        handler.post {
            accepting = false; queue.clear(); handler.removeCallbacks(drainTask)
            state(reason)
            if (busy) AirEngine.call(this, "cancelOperation") { } else finishStop()
        }
    }
    private fun finishStop() {
        AirEngine.call(this, "keepLinkWarm", mapOf("enabled" to false)) { }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun state(value: String) {
        Rules.prefs(this).edit().putString("last_state", value).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, LabActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, RelayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Air Signals").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "Stop alerts", stop).build()).build()
    }
    override fun onDestroy() {
        accepting = false; queue.clear(); handler.removeCallbacksAndMessages(null)
        if (lock?.isHeld == true) lock?.release()
        lock = null
        if (!busy) AirEngine.call(this, "keepLinkWarm", mapOf("enabled" to false)) { }
        instance = null
        super.onDestroy()
    }
}
