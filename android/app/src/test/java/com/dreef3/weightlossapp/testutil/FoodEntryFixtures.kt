package com.dreef3.weightlossapp.testutil

import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import java.time.Instant
import java.time.LocalDate

object FoodEntryFixtures {
    fun foodEntry(
        id: Long = 1,
        entryDate: LocalDate = LocalDate.parse("2026-04-03"),
        finalCalories: Int = 450,
        confidenceState: ConfidenceState = ConfidenceState.High,
        confirmationStatus: ConfirmationStatus = ConfirmationStatus.NotRequired,
    ) = FoodEntry(
        id = id,
        capturedAt = Instant.EPOCH,
        entryDate = entryDate,
        imagePath = "/tmp/meal-$id.jpg",
        estimatedCalories = finalCalories,
        finalCalories = finalCalories,
        confidenceState = confidenceState,
        detectedFoodLabel = "meal-$id",
        confidenceNotes = null,
        confirmationStatus = confirmationStatus,
        source = FoodEntrySource.AiEstimate,
        entryStatus = FoodEntryStatus.Ready,
    )
}
