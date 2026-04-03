package com.dreef3.weightlossapp.domain.usecase

import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository

class UpdateFoodEntryUseCase(
    private val repository: FoodEntryRepository,
) {
    suspend operator fun invoke(entry: FoodEntry): Long = repository.upsert(entry)
}
