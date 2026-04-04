package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.data.preferences.AppPreferences

class SelectableFoodEstimationEngine(
    private val preferences: AppPreferences,
    private val gemmaEngine: FoodEstimationEngine,
    private val smolVlmEngine: FoodEstimationEngine,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        when (preferences.readCalorieEstimationModel()) {
            CalorieEstimationModel.Gemma -> gemmaEngine.estimate(request)
            CalorieEstimationModel.SmolVlm -> smolVlmEngine.estimate(request)
        }

    override suspend fun warmUp(): Result<Unit> =
        when (preferences.readCalorieEstimationModel()) {
            CalorieEstimationModel.Gemma -> gemmaEngine.warmUp()
            CalorieEstimationModel.SmolVlm -> smolVlmEngine.warmUp()
        }
}
