package dev.codex.fitbitairlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Pure state-machine tests: no real alarms, services, sounds, or Bluetooth operations. */
@RunWith(AndroidJUnit4::class)
class TimeAlertStateTest {
    private class Clock : TimeClock {
        override var wall = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
        override var elapsed = 100000L
        override var boot = 1
        override var zone: ZoneId = ZoneId.of("UTC")
        fun advance(millis: Long) { wall += millis; elapsed += millis }
        fun at(instant: String) { wall = Instant.parse(instant).toEpochMilli() }
    }
    private class Store : TimeStateStore {
        var state = TimeAlertState()
        override fun read() = state
        override fun write(state: TimeAlertState) { this.state = state }
    }
    private data class Scheduled(val token: String, val deadline: TimeDeadline, val elapsed: Boolean)
    private class Backend : TimeAlarmBackend {
        var allowed = true
        var reject = false
        val scheduled = mutableMapOf<String, Scheduled>()
        override fun canSchedule() = allowed
        override fun schedule(key: String, token: String, deadline: TimeDeadline, elapsedBased: Boolean) {
            if (reject) throw SecurityException("revoked")
            scheduled[key] = Scheduled(token, deadline, elapsedBased)
        }
        override fun cancel(key: String) { scheduled.remove(key) }
    }
    private val clock = Clock()
    private val store = Store()
    private val backend = Backend()
    private val manager = TimeAlertManager(store, clock, backend)
    private fun fireTimer() = manager.fire("timer", store.state.timer!!.token)
    private fun alarm(hour: Int = 11, minute: Int = 0, days: Set<Int> = emptySet()) = WakeAlarm(hour = hour, minute = minute, days = days, label = "Test alarm")

    @Test fun countdownUsesElapsedTimeAcrossWallClockChanges() {
        manager.startTimer(60000)
        clock.advance(10000); clock.wall += 3600000
        assertEquals(50000L, store.state.timer!!.deadline!!.remaining(clock))
        assertNull(fireTimer())
        clock.advance(50000)
        assertNotNull(fireTimer())
    }
    @Test fun pauseResumeDoesNotCountPausedTime() {
        manager.startTimer(60000, 2); clock.advance(10000)
        val stale = store.state.timer!!.token
        manager.pauseTimer(); clock.advance(60000)
        assertEquals(50000L, store.state.timer!!.remaining)
        assertFalse(backend.scheduled.containsKey("timer"))
        manager.resumeTimer()
        assertEquals(50000L, store.state.timer!!.deadline!!.remaining(clock))
        assertNull(manager.fire("timer", stale))
    }
    @Test fun cancelledOrReplacedCountdownCannotFireFromAnOldBroadcast() {
        manager.startTimer(1000); val first = store.state.timer!!.token
        manager.cancelTimer(); clock.advance(1000)
        assertNull(manager.fire("timer", first))
        manager.startTimer(1000); val second = store.state.timer!!.token
        manager.startTimer(2000); clock.advance(1000)
        assertNull(manager.fire("timer", second))
        assertNull(fireTimer())
    }
    @Test fun expiryFiresOnceAndDoesNotDependOnNotificationAssignments() {
        manager.startTimer(1000); val token = store.state.timer!!.token
        clock.advance(1000)
        assertNotNull(fireTimer())
        assertNull(manager.fire("timer", token))
        assertEquals(1, store.state.active.size)
        assertEquals("finished", store.state.timer!!.state)
    }
    @Test fun veryLateExpiryIsRecordedAsMissedWithoutRinging() {
        manager.startTimer(1000); clock.advance(62000)
        assertNull(fireTimer())
        assertTrue(store.state.active.isEmpty())
        assertTrue(store.state.lastStatus.contains("missed"))
    }
    @Test fun rebootRestoresRemainingCountdownFromItsWallDeadline() {
        manager.startTimer(60000); clock.advance(20000); clock.boot++; clock.elapsed = 1000
        manager.restore(reboot = true)
        assertEquals(40000L, store.state.timer!!.deadline!!.remaining(clock))
        assertEquals(41000L, backend.scheduled.getValue("timer").deadline.elapsed)
    }
    @Test fun rebootAfterExpiryDoesNotRingAnOldTimer() {
        manager.startTimer(1000); clock.advance(5000); clock.boot++; clock.elapsed = 0
        manager.restore(reboot = true)
        assertEquals("finished", store.state.timer!!.state)
        assertTrue(store.state.active.isEmpty())
        assertFalse(backend.scheduled.containsKey("timer"))
    }
    @Test fun wallClockChangeRebasesSavedTimerWithoutChangingItsRemainingTime() {
        manager.startTimer(60000); clock.advance(10000); clock.wall -= 3600000
        manager.restore(clockChanged = true)
        assertEquals(50000L, store.state.timer!!.deadline!!.wall - clock.wall)
        assertEquals(50000L, store.state.timer!!.deadline!!.remaining(clock))
    }
    @Test fun weeklyAlarmChoosesTheNextSelectedWeekday() {
        val next = TimeMath.nextAlarm(alarm(hour = 9, days = setOf(1, 3)), clock.wall, clock.zone)
        assertEquals(Instant.parse("2026-11-18T09:00:00Z").toEpochMilli(), next)
    }
    @Test fun oneTimeAlarmAfterItsClockTimeMovesToTomorrow() {
        assertEquals(Instant.parse("2026-11-17T09:00:00Z").toEpochMilli(), TimeMath.nextAlarm(alarm(hour = 9), clock.wall, clock.zone))
    }
    @Test fun springDstGapIsResolvedToARealLocalTime() {
        clock.zone = ZoneId.of("America/New_York"); clock.at("2026-03-08T05:00:00Z")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), TimeMath.nextAlarm(alarm(2, 30), clock.wall, clock.zone))
    }
    @Test fun autumnDstAndClockRollbackCannotRingTheSameDailyAlarmTwice() {
        clock.zone = ZoneId.of("America/New_York"); clock.at("2026-11-01T04:00:00Z")
        manager.saveAlarm(alarm(1, 30, (1..7).toSet()))
        val first = store.state.alarms.single()
        clock.at("2026-11-01T05:30:00Z")
        assertNotNull(manager.fire("alarm/${first.id}", first.token))
        clock.at("2026-11-01T05:00:00Z")
        manager.restore(clockChanged = true)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(), store.state.alarms.single().deadline!!.wall)
    }
    @Test fun skipNextSurvivesCalendarRescheduling() {
        manager.saveAlarm(alarm(days = (1..7).toSet()))
        val id = store.state.alarms.single().id
        manager.skipNext(id); manager.restore(clockChanged = true)
        assertEquals(Instant.parse("2026-11-17T11:00:00Z").toEpochMilli(), store.state.alarms.single().deadline!!.wall)
    }
    @Test fun disablingOrDeletingAlarmInvalidatesItsPendingOccurrence() {
        manager.saveAlarm(alarm()); val original = store.state.alarms.single()
        manager.setAlarmEnabled(original.id, false); clock.advance(3600000)
        assertNull(manager.fire("alarm/${original.id}", original.token))
        manager.setAlarmEnabled(original.id, true); val updated = store.state.alarms.single()
        manager.deleteAlarm(original.id)
        assertNull(manager.fire("alarm/${original.id}", updated.token))
        assertTrue(backend.scheduled.isEmpty())
    }
    @Test fun snoozeCreatesANewOccurrenceAndUsesElapsedTime() {
        manager.startTimer(1000); clock.advance(1000)
        val ring = fireTimer()!!
        manager.snooze(ring.token)
        assertTrue(store.state.active.isEmpty())
        assertEquals(300000L, store.state.timer!!.deadline!!.remaining(clock))
        assertTrue(backend.scheduled.getValue("timer").elapsed)
        manager.stop(ring.token)
        clock.advance(300000)
        assertNotNull(fireTimer())
    }
    @Test fun snoozingAcrossMidnightDoesNotSkipTheNextRegularAlarm() {
        clock.at("2026-11-16T23:57:00Z")
        manager.saveAlarm(alarm(23, 58, (1..7).toSet()))
        clock.advance(60000)
        val original = store.state.alarms.single()
        val ring = manager.fire("alarm/${original.id}", original.token)!!
        manager.snooze(ring.token); clock.advance(300000)
        val snoozed = store.state.alarms.single()
        assertNotNull(manager.fire("alarm/${snoozed.id}", snoozed.token))
        assertEquals(Instant.parse("2026-11-17T23:58:00Z").toEpochMilli(), store.state.alarms.single().deadline!!.wall)
    }
    @Test fun simultaneousTimerAndAlarmCanBeStoppedIndependently() {
        clock.at("2026-11-16T10:59:59Z")
        manager.startTimer(1000); manager.saveAlarm(alarm())
        clock.advance(1000)
        val timer = fireTimer()!!; val alarm = store.state.alarms.single()
        assertNotNull(manager.fire("alarm/${alarm.id}", alarm.token))
        assertEquals(2, store.state.active.size)
        manager.stop(timer.token)
        assertEquals("alarm/${alarm.id}", store.state.active.single().key)
        manager.stop(); assertTrue(store.state.active.isEmpty())
    }
    @Test fun activeRingingEndsAfterTwoMinutesDespiteWallClockChanges() {
        manager.startTimer(1000); clock.advance(1000); fireTimer()
        clock.wall -= 86400000; clock.elapsed += 120000
        assertTrue(manager.snapshot().active.isEmpty())
    }
    @Test fun deniedAccessLeavesNoNewScheduleAndRestoresAfterGrant() {
        backend.allowed = false
        try { manager.startTimer(1000); fail("Expected exact-alarm access check") } catch (_: IllegalStateException) { }
        assertNull(store.state.timer)
        backend.allowed = true; manager.startTimer(60000)
        backend.allowed = false; manager.restore()
        assertFalse(store.state.permissionWasGranted)
        assertTrue(backend.scheduled.isEmpty())
        backend.allowed = true; manager.restore()
        assertTrue(backend.scheduled.containsKey("timer"))
    }
    @Test fun schedulingPermissionRaceIsRecordedForTheUser() {
        backend.reject = true
        try { manager.startTimer(1000); fail("Expected permission race") } catch (_: IllegalStateException) { }
        assertFalse(store.state.permissionWasGranted)
        assertTrue(store.state.lastStatus.contains("access changed"))
    }
    @Test fun persistenceRoundTripsTimerCalendarAndActiveOccurrence() {
        manager.startTimer(1000, 3); manager.saveAlarm(alarm(days = setOf(1, 5)))
        clock.advance(1000); fireTimer(); manager.setPhoneSound(false)
        assertEquals(store.state, TimeStateJson.decode(TimeStateJson.encode(store.state)))
        assertEquals(TimeAlertState(), TimeStateJson.decode("{}"))
    }
    @Test fun durationValidationPreventsInvalidSchedules() {
        for (value in listOf(0L, -1L, 604800001L, Long.MAX_VALUE)) {
            try { manager.startTimer(value); fail("Expected invalid duration") } catch (_: IllegalArgumentException) { }
        }
        assertNull(store.state.timer)
    }
}
