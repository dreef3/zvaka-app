package com.dreef3.weightlossapp.features.capture

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodCaptureFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsConfirmationForNonHighConfidenceState() {
        composeRule.setContent {
            FoodCaptureScreen(
                state = CaptureUiState(
                    imagePath = "/tmp/meal.jpg",
                    estimatedCalories = 700,
                    detectedFoodLabel = "mixed meal",
                    awaitingConfirmation = true,
                ),
                onAnalyzePhoto = {},
                onAccepted = {},
                onRejected = {},
                onRetakeAcknowledged = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Confirm food").assertExists()
        composeRule.onNodeWithText("Is this mixed meal?").assertExists()
    }
}
