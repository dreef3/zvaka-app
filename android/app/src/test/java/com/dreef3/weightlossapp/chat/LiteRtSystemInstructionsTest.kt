package com.dreef3.weightlossapp.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtSystemInstructionsTest {
    @Test
    fun includesSavedEntryReestimateGuidance() {
        val text = LiteRtSystemInstructions.text

        assertTrue(text.contains("Never use logFoodEntry for requests about an already saved meal"))
        assertTrue(text.contains("inspectEntry with that entryId"))
        assertTrue(text.contains("call reestimateEntry"))
        assertTrue(text.contains("Do not rely on"))
        assertTrue(text.contains("the exact wording being in English"))
        assertTrue(text.contains("Never create or suggest entries with future dates"))
    }
}
