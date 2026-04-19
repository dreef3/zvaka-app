package com.dreef3.weightlossapp.features.summary

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dreef3.weightlossapp.domain.model.DailySummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodaySummaryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTotalsAndOverBudgetNotice() {
        composeRule.setContent {
            TodaySummaryScreen(
                state = TodaySummaryUiState(
                    summary = DailySummary(
                        date = LocalDate.parse("2026-04-03"),
                        budgetCalories = 2000,
                        consumedCalories = 2300,
                        remainingCalories = -300,
                        status = "over",
                        entryCount = 2,
                        hasLimitedConfidenceEntries = false,
                    ),
                    isEmpty = false,
                ),
                onTakePhoto = {},
                onOpenTrends = {},
                onOpenHistoricalChat = {},
                onOpenMealDebug = {},
                onOpenManualEntry = {},
                onRetryEntry = {},
            )
        }

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("300 kcal over").assertIsDisplayed()
        composeRule.onNodeWithText("Consumed 2300 kcal").assertIsDisplayed()
    }
}
