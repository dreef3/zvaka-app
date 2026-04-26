package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.app.model.ModelInvocationCoordinator

class QueuedFoodEstimationEngine(
    private val delegate: FoodEstimationEngine,
    private val coordinator: ModelInvocationCoordinator,
    private val label: String,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        coordinator.runExclusive(label) {
            delegate.estimate(request)
        }

    override suspend fun warmUp(): Result<Unit> = coordinator.runExclusive(label) {
        delegate.warmUp()
    }
}
