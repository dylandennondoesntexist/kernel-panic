package app.kernelpanic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsConciseSafetyOnboarding() {
        composeRule.activity.getSharedPreferences("kernel-panic", MODE_PRIVATE).edit().clear().commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Welcome to Kernel Panic").assertIsDisplayed()
        composeRule.onNodeWithText("Got it").assertIsDisplayed()
    }

    private companion object {
        const val MODE_PRIVATE = 0
    }
}
