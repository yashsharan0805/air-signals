package dev.codex.fitbitairlab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** App selection only: never starts alert forwarding or touches Bluetooth. */
@RunWith(AndroidJUnit4::class)
class AppPickerUiTest {
    private val missingPackage = "dev.codex.removed.app"
    private var saved = emptyMap<String, Int>()
    @get:Rule(order = 0) val choices = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            saved = Rules.all(context)
            Rules.save(context, mapOf("com.android.settings" to 1, missingPackage to 2))
        }
        override fun after() { Rules.save(InstrumentationRegistry.getInstrumentation().targetContext, saved) }
    }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<LabActivity>()
    private fun enabled() {
        ui.onNodeWithTag("nav-apps").performClick()
        ui.onNodeWithTag("app-filter-enabled").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("app-com.android.settings").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun turnOff(pkg: String) {
        ui.onNodeWithTag("app-$pkg").performClick()
        ui.onNodeWithTag("assign-0").performClick()
    }
    @Test fun iconsAreAvailableForInstalledAndMissingAssignedApps() {
        enabled()
        ui.onNodeWithTag("app-icon-com.android.settings", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("app-icon-$missingPackage", useUnmergedTree = true).assertExists()
        assertNotNull(ViewModelProvider(ui.activity)[SignalsModel::class.java].apps!!.first { it.packageName == "com.android.settings" }.icon)
        assertEquals(2, Rules.all(ui.activity)[missingPackage])
    }
    @Test fun enabledFilterCombinesWithSearchAndAllAppsRestoresDisabledResults() {
        enabled()
        val disabled = ViewModelProvider(ui.activity)[SignalsModel::class.java].apps!!.first { it.packageName !in Rules.all(ui.activity) }
        ui.onNodeWithText("Search apps").performTextReplacement(disabled.packageName)
        ui.onNodeWithText("No enabled apps match your search.").assertExists()
        ui.onNodeWithTag("app-${disabled.packageName}").assertDoesNotExist()
        ui.onNodeWithTag("app-filter-all").performClick()
        ui.onNodeWithTag("app-${disabled.packageName}").assertExists()
    }
    @Test fun enabledFilterFollowsDraftDiscardAndSaveAcrossRecreation() {
        enabled(); turnOff("com.android.settings")
        ui.onNodeWithTag("app-com.android.settings").assertDoesNotExist()
        assertEquals(1, Rules.all(ui.activity)["com.android.settings"])
        ui.onNodeWithText("Discard changes").performClick()
        ui.onNodeWithTag("app-com.android.settings").assertExists()
        turnOff("com.android.settings")
        ui.onNodeWithText("Save").performClick()
        assertFalse(Rules.all(ui.activity).containsKey("com.android.settings"))
        ui.activityRule.scenario.recreate(); ui.waitForIdle()
        ui.onNodeWithTag("app-filter-enabled").assertIsSelected()
        ui.onNodeWithTag("app-com.android.settings").assertDoesNotExist()
        ui.onNodeWithTag("app-$missingPackage").assertExists()
    }
    @Test fun noEnabledAppsHasAClearEmptyState() {
        enabled(); turnOff("com.android.settings"); turnOff(missingPackage)
        ui.onNodeWithText("No apps enabled yet.").assertExists()
        ui.onNodeWithText("Save").performClick()
        assertTrue(Rules.all(ui.activity).isEmpty())
        ui.onNodeWithTag("app-filter-all").performClick()
        ui.onNodeWithText("No apps enabled yet.").assertDoesNotExist()
    }
}
