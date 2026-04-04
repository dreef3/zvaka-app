package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.domain.model.ConfidenceState

data class FoodEstimationResult(
    val estimatedCalories: Int,
    val confidenceState: ConfidenceState,
    val detectedFoodLabel: String?,
    val confidenceNotes: String?,
    val detectedItems: List<String> = emptyList(),
    val debugInteractionLog: String? = null,
)
