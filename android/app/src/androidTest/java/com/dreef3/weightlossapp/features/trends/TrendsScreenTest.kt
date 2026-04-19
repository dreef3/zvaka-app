package com.dreef3.weightlossapp.features.trends

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TrendsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsPartialHistoryMessageAndWindowTotals() {
        composeRule.setContent {
            TrendsScreen(
                state = TrendsUiState(
                    selectedWindow = TrendWindowType.Last30Days,
                    window = TrendWindow(
                        windowType = TrendWindowType.Last30Days,
                        startDate = LocalDate.parse("2026-03-05"),
                        endDate = LocalDate.parse("2026-04-03"),
                        daysIncluded = 11,
                        totalConsumedCalories = 4800,
                        totalBudgetCalories = 6200,
                        averageConsumedCalories = 436.0,
                        averageRemainingCalories = 127.0,
                        isPartial = true,
                    ),
                ),
                onSelectWindow = {},
                onOpenHistoricalChat = {},
                onOpenMealDebug = {},
                onRetryEntry = {},
            )
        }

        composeRule.onNodeWithText("Total eaten").assertIsDisplayed()
        composeRule.onNodeWithText("4800").assertIsDisplayed()
        composeRule.onNodeWithText("Showing partial history for this window.").assertIsDisplayed()
    }
}
