package com.dreef3.weightlossapp.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptBuilderTest {
    @Test
    fun includesRecentHistoryAndTrustedContext() {
        val prompt = CoachPromptBuilder.buildPrompt(
            history = listOf(
                DietChatMessage(role = ChatRole.User, text = "Breakfast was eggs"),
                DietChatMessage(role = ChatRole.Assistant, text = "Logged that."),
            ),
            message = "Please re-estimate the last saved meal",
            snapshot = DietChatSnapshot(
                todayBudgetCalories = 2000,
                todayConsumedCalories = 600,
                todayRemainingCalories = 1400,
                entries = listOf(
                    DietEntryContext(
                        entryId = 12,
                        dateIso = "2026-04-19",
                        description = "Pasta",
                        finalCalories = 550,
                        estimatedCalories = 550,
                        needsManual = false,
                        source = "AiEstimate",
                    ),
                ),
            ),
        )

        assertTrue(prompt.contains("Conversation so far:"))
        assertTrue(prompt.contains("User: Breakfast was eggs"))
        assertTrue(prompt.contains("Coach: Logged that."))
        assertTrue(prompt.contains("Trusted user diet context:"))
        assertTrue(prompt.contains("Today's budget calories: 2000"))
        assertTrue(prompt.contains("- id=12, 2026-04-19: Pasta, 550 kcal, source=AiEstimate, needsManual=false"))
        assertTrue(prompt.trimEnd().endsWith("User: Please re-estimate the last saved meal"))
    }

    @Test
    fun omitsConversationHeaderWhenHistoryIsEmpty() {
        val prompt = CoachPromptBuilder.buildPrompt(
            history = emptyList(),
            message = "I had yogurt",
            snapshot = DietChatSnapshot(
                todayBudgetCalories = null,
                todayConsumedCalories = 0,
                todayRemainingCalories = null,
                entries = emptyList(),
            ),
        )

        assertFalse(prompt.contains("Conversation so far:"))
        assertTrue(prompt.contains("Recent food entries:\n- none"))
    }
}
