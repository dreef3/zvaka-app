package com.dreef3.weightlossapp.features.capture

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodCaptureRetakeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsRetakeAcknowledgePathAfterRejectedEstimate() {
        composeRule.setContent {
            FoodCaptureScreen(
                state = CaptureUiState(
                    shouldRetake = true,
                    errorMessage = "Take another photo.",
                ),
                onAnalyzePhoto = {},
                onAccepted = {},
                onRejected = {},
                onRetakeAcknowledged = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Take another photo.").assertExists()
        composeRule.onNodeWithText("Retake acknowledged").assertExists()
    }
}
