package dev.codex.fitbitairlab

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures actual Compose screens for human visual review; no BLE operations. */
@RunWith(AndroidJUnit4::class)
class UiScreenshots {
    @get:Rule val ui = createAndroidComposeRule<LabActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        // Capture the whole Android window, including dialogs, without relying
        // on Compose's PixelCopy deadline on a busy emulator renderer.
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(ui.activity.filesDir, "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun captureScreens() {
        capture("home")
        ui.onNodeWithText("Apps", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Search apps").performTextInput("Settings")
        ui.waitUntil(10000) { ui.onAllNodesWithTag("app-com.android.settings").fetchSemanticsNodes().isNotEmpty() }
        capture("apps")
        ui.onNodeWithText("Patterns", useUnmergedTree = true).performClick()
        capture("patterns")
        ui.onAllNodesWithText("Edit").onFirst().performClick()
        ui.onNode(hasText("2 groups") and hasClickAction()).performClick()
        ui.onNodeWithText("Edit signal A").assertIsDisplayed()
        capture("editor")
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        ui.onNodeWithTag("mute-while-screen-on").assertIsDisplayed()
        capture("settings")
    }
}
