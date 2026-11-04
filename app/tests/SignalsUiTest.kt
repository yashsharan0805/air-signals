package dev.codex.fitbitairlab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI and local settings only: never starts the relay or sends BLE commands. */
@RunWith(AndroidJUnit4::class)
class SignalsUiTest {
    @get:Rule val ui = createAndroidComposeRule<LabActivity>()
    @Test fun missingPermissionsPreventStartingAlerts() {
        ui.onNodeWithText("Start alerts").assertIsNotEnabled()
        ui.onNodeWithText("Complete setup below to start.").assertExists()
    }
    @Test fun appSearchHasEmptyStateAndDoesNotChangeSavedRules() {
        val before = Rules.all(ui.activity)
        ui.onNodeWithText("Apps", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Search apps").performTextInput("zzzz-no-matching-app-97531")
        ui.waitUntil(10000) { ui.onAllNodesWithText("No apps match your search.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(before, Rules.all(ui.activity))
    }
    @Test fun cancellingPatternEditLeavesSavedPatternUnchanged() {
        val before = Rules.groups(ui.activity, 1)
        ui.onNodeWithText("Patterns", useUnmergedTree = true).performClick()
        ui.onAllNodesWithText("Edit").onFirst().performClick()
        ui.onNode(hasText("3 groups") and hasClickAction()).performClick()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(before, Rules.groups(ui.activity, 1))
    }
    @Test fun patternChangesPersistAcrossActivityRecreation() {
        val prefs = Rules.prefs(ui.activity)
        val oldGroups = Rules.groups(ui.activity, 1)
        val oldGap = Rules.gap(ui.activity, 1)
        try {
            ui.onNodeWithText("Patterns", useUnmergedTree = true).performClick()
            ui.onAllNodesWithText("Edit").onFirst().performClick()
            ui.onNode(hasText("2 groups") and hasClickAction()).performClick()
            ui.onNodeWithText("2.4 seconds").performClick()
            ui.onNodeWithText("Save").performClick()
            ui.activityRule.scenario.recreate()
            ui.waitForIdle()
            assertEquals(2, Rules.groups(ui.activity, 1))
            assertEquals(2400, Rules.gap(ui.activity, 1))
        } finally { prefs.edit().putInt("groups_1", oldGroups).putInt("gap_1", oldGap).commit() }
    }
    @Test fun appAssignmentsOnlyPersistAfterSave() {
        val before = Rules.all(ui.activity)
        try {
            ui.onNodeWithText("Apps", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Search apps").performTextInput("Settings")
            ui.waitUntil(10000) { ui.onAllNodesWithTag("app-com.android.settings").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("app-com.android.settings").performClick()
            ui.onNodeWithTag("assign-1").performClick()
            assertEquals(before, Rules.all(ui.activity))
            ui.onNodeWithText("Save").performClick()
            ui.waitForIdle()
            assertEquals(1, Rules.all(ui.activity)["com.android.settings"])
            ui.activityRule.scenario.recreate()
            assertEquals(1, Rules.all(ui.activity)["com.android.settings"])
        } finally { Rules.save(ui.activity, before) }
    }

}
