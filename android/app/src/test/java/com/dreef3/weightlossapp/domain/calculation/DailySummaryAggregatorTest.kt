package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DailySummaryAggregatorTest {
    private val aggregator = SummaryAggregator()
    private val date = LocalDate.parse("2026-04-03")

    @Test
    fun excludesRejectedAndDeletedEntriesFromConsumedCalories() {
        val summary = aggregator.buildSummary(
            date = date,
            budgetCalories = 2000,
            entries = listOf(
                entry(id = 1, finalCalories = 600, confidenceState = ConfidenceState.High),
                entry(
                    id = 2,
                    finalCalories = 300,
                    confidenceState = ConfidenceState.High,
                    confirmationStatus = ConfirmationStatus.Rejected,
                ),
                entry(
                    id = 3,
                    finalCalories = 400,
                    confidenceState = ConfidenceState.High,
                    deletedAt = Instant.parse("2026-04-03T12:00:00Z"),
                ),
            ),
        )

        assertEquals(600, summary.consumedCalories)
        assertEquals(1400, summary.remainingCalories)
        assertEquals(1, summary.entryCount)
    }

    @Test
    fun marksSummaryWhenLimitedConfidenceEntriesArePresent() {
        val summary = aggregator.buildSummary(
            date = date,
            budgetCalories = 1800,
            entries = listOf(
                entry(id = 1, finalCalories = 900, confidenceState = ConfidenceState.NonHigh),
            ),
        )

        assertTrue(summary.hasLimitedConfidenceEntries)
        assertEquals("under", summary.status)
    }

    @Test
    fun excludesProcessingAndManualRequiredEntriesFromSummary() {
        val summary = aggregator.buildSummary(
            date = date,
            budgetCalories = 2000,
            entries = listOf(
                entry(id = 1, finalCalories = 400, confidenceState = ConfidenceState.High),
                entry(id = 2, finalCalories = 0, confidenceState = ConfidenceState.Failed, entryStatus = FoodEntryStatus.Processing),
                entry(id = 3, finalCalories = 0, confidenceState = ConfidenceState.Failed, entryStatus = FoodEntryStatus.NeedsManual),
            ),
        )

        assertEquals(400, summary.consumedCalories)
        assertEquals(1, summary.entryCount)
    }

    private fun entry(
        id: Long,
        finalCalories: Int,
        confidenceState: ConfidenceState,
        confirmationStatus: ConfirmationStatus = ConfirmationStatus.Accepted,
        entryStatus: FoodEntryStatus = FoodEntryStatus.Ready,
        deletedAt: Instant? = null,
    ) = FoodEntry(
        id = id,
        capturedAt = Instant.parse("2026-04-03T08:00:00Z"),
        entryDate = date,
        imagePath = "/tmp/meal-$id.jpg",
        estimatedCalories = finalCalories,
        finalCalories = finalCalories,
        confidenceState = confidenceState,
        detectedFoodLabel = "meal-$id",
        confidenceNotes = null,
        confirmationStatus = confirmationStatus,
        source = FoodEntrySource.AiEstimate,
        entryStatus = entryStatus,
        deletedAt = deletedAt,
    )
}
