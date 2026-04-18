package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.data.preferences.AppPreferences

class SelectableFoodEstimationEngine(
    private val preferences: AppPreferences,
    private val gemmaEngine: FoodEstimationEngine,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> {
        val selected = preferences.readCalorieEstimationModel()
        val engine = when (selected) {
            CalorieEstimationModel.Gemma -> gemmaEngine
        }
        return engine.estimate(request)
    }

    override suspend fun warmUp(): Result<Unit> {
        val selected = preferences.readCalorieEstimationModel()
        val engine = when (selected) {
            CalorieEstimationModel.Gemma -> gemmaEngine
        }
        return engine.warmUp()
    }
}
