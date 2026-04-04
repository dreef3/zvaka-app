package com.dreef3.weightlossapp.features.capture

data class CaptureUiState(
    val imagePath: String? = null,
    val estimatedCalories: Int? = null,
    val detectedFoodLabel: String? = null,
    val confidenceNotes: String? = null,
    val isLoading: Boolean = false,
    val awaitingConfirmation: Boolean = false,
    val shouldRetake: Boolean = false,
    val savedEntryId: Long? = null,
    val errorMessage: String? = null,
    val isDownloadingModel: Boolean = false,
    val modelAvailable: Boolean = false,
    val modelStatusMessage: String? = null,
    val modelDownloadProgressPercent: Int? = null,
)
