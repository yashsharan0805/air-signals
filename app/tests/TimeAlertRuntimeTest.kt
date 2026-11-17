package dev.codex.fitbitairlab

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only: exercises real AlarmManager broadcasts, sound service, and notification actions. No BLE permission. */
@RunWith(AndroidJUnit4::class)
class TimeAlertRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var before: TimeAlertState? = null
    private var muteBefore = false
    private fun manager() = TimeAlerts.manager(context)
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the alarm runtime", condition())
    }
    @Before fun setup() {
        assumeTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        assumeTrue(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        assumeTrue(AndroidAlarmBackend(context).canSchedule() && TimeAlerts.notificationsAllowed(context))
        before = AndroidTimeStore(context).read()
        muteBefore = Rules.muteWhileScreenOn(context)
        manager().stop(); manager().cancelTimer(); manager().snapshot().alarms.forEach { manager().deleteAlarm(it.id) }
        manager().setPhoneSound(true)
    }
    @After fun cleanup() {
        before?.let { old ->
            manager().stop(); manager().cancelTimer(); manager().snapshot().alarms.forEach { manager().deleteAlarm(it.id) }
            TimeAlerts.sync(context)
            waitFor { TimeAlertService.instance == null }
            AndroidTimeStore(context).write(old); manager().restore()
            Rules.prefs(context).edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, muteBefore).commit()
        }
    }
    @Test fun backgroundTimerRingsWithScreenMuteEnabledAndStopActionWorks() {
        Rules.prefs(context).edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, true).commit()
        manager().startTimer(1500)
        waitFor { manager().snapshot().active.isNotEmpty() && TimeAlertService.instance != null }
        assertFalse(RelayService.accessGranted(context))
        val ring = manager().snapshot().active.single()
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.tag == "scheduled-alert/${ring.token}" }.notification
        notification.actions.first { it.title.toString() == "Stop" }.actionIntent.send()
        waitFor { manager().snapshot().active.isEmpty() && TimeAlertService.instance == null }
        assertEquals("finished", manager().snapshot().timer!!.state)
    }
    @Test fun notificationSnoozeSchedulesANewTimerOccurrence() {
        manager().startTimer(1500)
        waitFor { manager().snapshot().active.isNotEmpty() && TimeAlertService.instance != null }
        val ring = manager().snapshot().active.single()
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.tag == "scheduled-alert/${ring.token}" }.notification
        notification.actions.first { it.title.toString() == "Snooze 5 min" }.actionIntent.send()
        waitFor { manager().snapshot().active.isEmpty() && manager().snapshot().timer?.state == "running" }
        val timer = manager().snapshot().timer!!
        assertNotEquals(ring.token, timer.token)
        assertTrue(timer.deadline!!.remaining(AndroidTimeClock(context)) in 285000L..300000L)
    }
    @Test fun calendarAlarmBroadcastAndRingingExpiryUseTheRealService() {
        val clock = AndroidTimeClock(context)
        val alarm = WakeAlarm(hour = 23, minute = 59, deadline = TimeDeadline.after(clock, 1500))
        val state = manager().snapshot()
        AndroidTimeStore(context).write(state.copy(alarms = listOf(alarm)))
        AndroidAlarmBackend(context).schedule("alarm/${alarm.id}", alarm.token, alarm.deadline!!, false)
        waitFor { manager().snapshot().active.isNotEmpty() && TimeAlertService.instance != null }
        assertFalse(manager().snapshot().alarms.single().enabled)
        val ringing = manager().snapshot()
        AndroidTimeStore(context).write(ringing.copy(active = ringing.active.map { it.copy(expires = TimeDeadline.after(clock, 500)) }))
        waitFor { manager().snapshot().active.isEmpty() && TimeAlertService.instance == null }
    }
    @Test fun notificationExpiresEvenWhenTheRingingServiceStops() {
        manager().startTimer(1500)
        waitFor { manager().snapshot().active.isNotEmpty() && TimeAlertService.instance != null }
        val state = manager().snapshot()
        val ring = state.active.single().copy(expires = TimeDeadline.after(AndroidTimeClock(context), 1500))
        AndroidTimeStore(context).write(state.copy(active = listOf(ring)))
        TimeAlerts.sync(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(notifications.activeNotifications.first { it.tag == "scheduled-alert/${ring.token}" }.notification.timeoutAfter in 1L..1500L)
        context.stopService(Intent(context, TimeAlertService::class.java))
        waitFor { TimeAlertService.instance == null && notifications.activeNotifications.none { it.tag == "scheduled-alert/${ring.token}" } }
    }
}
