package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.chat.usesLlamaBackend
import com.dreef3.weightlossapp.data.preferences.AppPreferences

class SelectableFoodEstimationEngine(
    private val preferences: AppPreferences,
    private val liteRtEngine: FoodEstimationEngine,
    private val llamaEngine: FoodEstimationEngine,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> {
        val coachModel = preferences.readCoachModel()
        val engine = if (coachModel.usesLlamaBackend()) llamaEngine else liteRtEngine
        return engine.estimate(request)
    }

    override suspend fun warmUp(): Result<Unit> {
        val coachModel = preferences.readCoachModel()
        val engine = if (coachModel.usesLlamaBackend()) llamaEngine else liteRtEngine
        return engine.warmUp()
    }
}
