package com.example.onetapvpn

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainScreenStartsSimpleAndKeepsProfileToolsOptional() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.status_disconnected)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.connect)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.advanced_settings)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.import_file)).assertDoesNotExist()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.advanced_settings)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.import_file)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.paste_clipboard)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.export_profile)).assertIsDisplayed()
    }
}
