package dev.codex.fitbitairlab

import android.text.format.DateFormat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimeAlertsUiTest {
    @get:Rule val ui = createAndroidComposeRule<LabActivity>()
    private fun show(matcher: SemanticsMatcher) { ui.onNodeWithTag("time-alert-list").performScrollToNode(matcher) }
    private fun open() { ui.onNodeWithTag("nav-timers").performClick() }
    private fun restore(state: TimeAlertState) {
        val manager = TimeAlerts.manager(ui.activity)
        manager.stop(); manager.cancelTimer()
        manager.snapshot().alarms.forEach { manager.deleteAlarm(it.id) }
        AndroidTimeStore(ui.activity).write(state)
        manager.restore(); TimeAlerts.sync(ui.activity)
    }
    @Test fun zeroDurationCannotStartATimer() {
        open(); show(hasTestTag("timer-minutes"))
        ui.onNodeWithTag("timer-minutes").performTextReplacement("0")
        show(hasTestTag("start-timer"))
        ui.onNodeWithTag("start-timer").assertIsNotEnabled()
        ui.onNodeWithText("Choose a duration from one second to seven days.").assertExists()
    }
    @Test fun phoneSoundPreferencePersistsAcrossActivityRecreation() {
        val before = AndroidTimeStore(ui.activity).read()
        try {
            open(); show(hasTestTag("phone-alarm-sound"))
            ui.onNodeWithTag("phone-alarm-sound").performClick()
            assertEquals(!before.phoneSound, AndroidTimeStore(ui.activity).read().phoneSound)
            ui.activityRule.scenario.recreate(); ui.waitForIdle()
            show(hasTestTag("phone-alarm-sound"))
            if (before.phoneSound) ui.onNodeWithTag("phone-alarm-sound").assertIsOff() else ui.onNodeWithTag("phone-alarm-sound").assertIsOn()
        } finally { restore(before) }
    }
    @Test fun countdownCanStartPauseRecreateResumeAndCancel() {
        assumeTrue(AndroidAlarmBackend(ui.activity).canSchedule() && TimeAlerts.notificationsAllowed(ui.activity))
        val before = AndroidTimeStore(ui.activity).read()
        try {
            open(); show(hasTestTag("start-timer")); ui.onNodeWithTag("start-timer").performClick()
            show(hasText("Pause") and hasClickAction()); ui.onNodeWithText("Pause").performClick()
            assertEquals("paused", AndroidTimeStore(ui.activity).read().timer!!.state)
            ui.activityRule.scenario.recreate(); ui.waitForIdle()
            show(hasText("Resume") and hasClickAction()); ui.onNodeWithText("Resume").performClick()
            assertEquals("running", AndroidTimeStore(ui.activity).read().timer!!.state)
            show(hasText("Cancel timer") and hasClickAction()); ui.onNodeWithText("Cancel timer").performClick()
            assertEquals("cancelled", AndroidTimeStore(ui.activity).read().timer!!.state)
        } finally { restore(before) }
    }
    @Test fun alarmEditorSavesRepeatingAlarmAndTogglePersists() {
        assumeTrue(AndroidAlarmBackend(ui.activity).canSchedule() && TimeAlerts.notificationsAllowed(ui.activity))
        val before = AndroidTimeStore(ui.activity).read()
        try {
            open(); show(hasTestTag("alarm-tab")); ui.onNodeWithTag("alarm-tab").performClick()
            show(hasTestTag("add-alarm")); ui.onNodeWithTag("add-alarm").performClick()
            ui.onNodeWithTag("alarm-clock-picker").assertExists()
            ui.onNodeWithTag("time-picker-mode").performClick()
            val inputs = ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("alarm-time-input")))
            inputs.onFirst().performTextReplacement(if (DateFormat.is24HourFormat(ui.activity)) "23" else "11")
            inputs.onLast().performTextReplacement("59")
            if (!DateFormat.is24HourFormat(ui.activity)) ui.onNodeWithText("PM", useUnmergedTree = true).performClick()
            ui.activityRule.scenario.recreate(); ui.waitForIdle()
            ui.onNodeWithTag("confirm-alarm-time").performClick()
            ui.onNodeWithTag("alarm-label").performTextReplacement("UI verification")
            ui.onNodeWithText(DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, Locale.getDefault())).performScrollTo().performClick()
            ui.onNodeWithText("Save").performClick()
            val alarm = AndroidTimeStore(ui.activity).read().alarms.first { it.label == "UI verification" }
            assertEquals(23, alarm.hour); assertEquals(59, alarm.minute)
            assertEquals(setOf(1), alarm.days)
            ui.activityRule.scenario.recreate(); ui.waitForIdle()
            show(hasTestTag("alarm-toggle-${alarm.id}")); ui.onNodeWithTag("alarm-toggle-${alarm.id}").performClick()
            assertFalse(AndroidTimeStore(ui.activity).read().alarms.first { it.id == alarm.id }.enabled)
        } finally { restore(before) }
    }
    @Test fun addAlarmStartsWithClockDialAndCancelCreatesNothing() {
        assumeTrue(AndroidAlarmBackend(ui.activity).canSchedule() && TimeAlerts.notificationsAllowed(ui.activity))
        val before = AndroidTimeStore(ui.activity).read().alarms
        open(); show(hasTestTag("alarm-tab")); ui.onNodeWithTag("alarm-tab").performClick()
        show(hasTestTag("add-alarm")); ui.onNodeWithTag("add-alarm").performClick()
        ui.onNodeWithTag("alarm-clock-picker").assertExists()
        ui.onNodeWithTag("alarm-time-input").assertDoesNotExist()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(before, AndroidTimeStore(ui.activity).read().alarms)
    }
    @Test fun cancellingAChangedClockTimePreservesTheOriginalAlarmTime() {
        assumeTrue(AndroidAlarmBackend(ui.activity).canSchedule() && TimeAlerts.notificationsAllowed(ui.activity))
        val before = AndroidTimeStore(ui.activity).read()
        try {
            val alarm = WakeAlarm(label = "Picker cancellation", hour = 13, minute = 15)
            TimeAlerts.manager(ui.activity).saveAlarm(alarm)
            open(); show(hasTestTag("alarm-tab")); ui.onNodeWithTag("alarm-tab").performClick()
            show(hasText("Edit") and hasClickAction()); ui.onNodeWithText("Edit").performClick()
            ui.onNodeWithTag("edit-alarm-time").performClick()
            ui.onNodeWithTag("time-picker-mode").performClick()
            ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("alarm-time-input"))).onFirst().performTextReplacement("10")
            ui.onNodeWithText("Cancel").performClick()
            ui.onNodeWithText("Save").performClick()
            val saved = AndroidTimeStore(ui.activity).read().alarms.first { it.id == alarm.id }
            assertEquals(13, saved.hour); assertEquals(15, saved.minute)
        } finally { restore(before) }
    }
}
