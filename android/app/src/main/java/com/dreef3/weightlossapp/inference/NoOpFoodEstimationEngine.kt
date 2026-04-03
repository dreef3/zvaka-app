package com.dreef3.weightlossapp.inference

class NoOpFoodEstimationEngine : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        Result.failure(IllegalStateException("LiteRT-LM engine is not configured yet"))
}
