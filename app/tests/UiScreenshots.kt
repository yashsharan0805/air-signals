package dev.codex.fitbitairlab

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
        val directory = File(ui.activity.filesDir, "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
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
        // Capture the window for the sheet: Compose root capture may return its background window.
        ui.onNodeWithText("Edit signal A").assertIsDisplayed()
        ui.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(File(ui.activity.filesDir, "ui-captures"), "editor.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        ui.onNodeWithText("Cancel").performClick()
    }
}
