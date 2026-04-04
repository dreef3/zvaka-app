package com.dreef3.weightlossapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodEstimationTextParserTest {
    @Test
    fun parsesStructuredDescriptionAndCalories() {
        val result = FoodEstimationTextParser.parse(
            """
            description: banana
            calories: 105
            """.trimIndent(),
        )

        assertEquals(105, result.estimatedCalories)
        assertEquals("banana", result.detectedFoodLabel)
    }

    @Test
    fun fallsBackToPlainNumberOutput() {
        val result = FoodEstimationTextParser.parse("160.")

        assertEquals(160, result.estimatedCalories)
        assertNull(result.detectedFoodLabel)
    }
}
