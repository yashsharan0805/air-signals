package dev.codex.fitbitairlab

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises delivery gating without starting Bluetooth or the foreground service. */
@RunWith(AndroidJUnit4::class)
class ScreenOnMuteTest {
    private fun alert(id: String, time: Long = 0) = PendingAlert("selected.app", 2, 1800, time, id)

    @Test fun queueCapacityCoalescingExpiryAndDuplicatesStillWork() {
        assertEquals(true, QueueChecks.run()["passed"])
    }

    @Test fun androidScreenStateOnlyMutesWhenThePreferenceIsEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Rules.prefs(context)
        val existed = prefs.contains(Rules.MUTE_WHILE_SCREEN_ON)
        val before = Rules.muteWhileScreenOn(context)
        val power = context.getSystemService(PowerManager::class.java)
        fun screen(awake: Boolean) {
            val key = if (awake) "KEYCODE_WAKEUP" else "KEYCODE_SLEEP"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $key")).use { it.readBytes() }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (power.isInteractive != awake && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals("Android interactive state", awake, power.isInteractive)
        }
        try {
            prefs.edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, true).commit()
            screen(true)
            assertTrue(Rules.alertsMuted(context))
            screen(false)
            assertFalse(Rules.alertsMuted(context))
            screen(true)
            prefs.edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, false).commit()
            assertFalse(Rules.alertsMuted(context))
        } finally {
            screen(true)
            prefs.edit().apply {
                if (existed) putBoolean(Rules.MUTE_WHILE_SCREEN_ON, before)
                else remove(Rules.MUTE_WHILE_SCREEN_ON)
            }.commit()
        }
    }

    @Test fun screenOnNotificationsAreDiscardedAndNeverReplayed() {
        var screenOn = true
        val queue = AlertQueue(suppressAlerts = { screenOn })
        assertEquals("screen_on", queue.offer(alert("visible"), 0))
        screenOn = false
        assertNull(queue.take(1))
        assertEquals("queued", queue.offer(alert("new", 2), 2))
        assertEquals("new", queue.take(3)?.identity)
    }

    @Test fun wakingBeforeDeliveryDiscardsThePendingQueue() {
        var screenOn = false
        val queue = AlertQueue(suppressAlerts = { screenOn })
        assertEquals("queued", queue.offer(alert("pending"), 0))
        screenOn = true
        assertNull(queue.take(1))
        screenOn = false
        assertNull(queue.take(2))
    }

    @Test fun enablingMuteWhileAwakeDropsPendingAlertsButDisablingAllowsNewOnes() {
        var enabled = false
        val queue = AlertQueue(suppressAlerts = { enabled })
        assertEquals("queued", queue.offer(alert("pending"), 0))
        enabled = true
        assertTrue(queue.discardIfSuppressed())
        assertEquals("screen_on", queue.offer(alert("muted", 1), 1))
        enabled = false
        assertNull(queue.take(2))
        assertEquals("queued", queue.offer(alert("allowed", 3), 3))
        assertEquals("allowed", queue.take(4)?.identity)
    }

    @Test fun absentPreferenceKeepsExistingNotificationBehavior() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Rules.prefs(context)
        val existed = prefs.contains(Rules.MUTE_WHILE_SCREEN_ON)
        val before = Rules.muteWhileScreenOn(context)
        try {
            prefs.edit().remove(Rules.MUTE_WHILE_SCREEN_ON).commit()
            assertFalse(Rules.muteWhileScreenOn(context))
            assertFalse(Rules.alertsMuted(context))
        } finally {
            if (existed) prefs.edit().putBoolean(Rules.MUTE_WHILE_SCREEN_ON, before).commit()
        }
    }
}
