package com.dreef3.weightlossapp.inference

data class FoodEstimationRequest(
    val imagePath: String,
    val capturedAtEpochMs: Long,
    val locale: String? = null,
    val userContext: String? = null,
    val preferredDescription: String? = null,
)

interface FoodEstimationEngine {
    suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult>

    suspend fun warmUp(): Result<Unit> = Result.success(Unit)
}
