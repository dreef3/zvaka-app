package com.dreef3.weightlossapp.domain.usecase

import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository

class SaveManualCaloriesUseCase(
    private val repository: FoodEntryRepository,
) {
    suspend fun save(entry: FoodEntry, calories: Int): Long =
        repository.upsert(
            entry.copy(
                estimatedCalories = entry.estimatedCalories.takeIf { it > 0 } ?: calories,
                finalCalories = calories,
                detectedFoodLabel = entry.detectedFoodLabel ?: "Manual entry",
                confidenceNotes = "Calories entered manually.",
                source = FoodEntrySource.UserCorrected,
                entryStatus = FoodEntryStatus.Ready,
            ),
        )
}
