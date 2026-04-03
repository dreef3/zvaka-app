package com.dreef3.weightlossapp.inference

sealed interface FoodEstimationError {
    data object ModelUnavailable : FoodEstimationError
    data object ModelLoadFailed : FoodEstimationError
    data object UnreadableImage : FoodEstimationError
    data object EstimationFailed : FoodEstimationError
    data object InferenceTimeout : FoodEstimationError
}
