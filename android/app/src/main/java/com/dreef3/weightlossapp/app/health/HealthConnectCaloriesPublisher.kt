package com.dreef3.weightlossapp.app.health

import com.dreef3.weightlossapp.domain.model.FoodEntry

interface HealthConnectCaloriesPublisher {
    suspend fun hasWritePermission(): Boolean

    suspend fun upsertCalories(entry: FoodEntry)
}
