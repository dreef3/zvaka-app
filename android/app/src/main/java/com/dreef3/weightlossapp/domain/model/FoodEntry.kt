package com.dreef3.weightlossapp.domain.model

import java.time.Instant
import java.time.LocalDate

enum class ConfidenceState {
    High,
    NonHigh,
    Failed,
}

enum class ConfirmationStatus {
    NotRequired,
    Accepted,
    Rejected,
}

enum class FoodEntrySource {
    AiEstimate,
    UserCorrected,
}

enum class FoodEntryStatus {
    Processing,
    Ready,
    NeedsManual,
}

data class FoodEntry(
    val id: Long = 0,
    val capturedAt: Instant,
    val entryDate: LocalDate,
    val imagePath: String,
    val estimatedCalories: Int,
    val finalCalories: Int,
    val confidenceState: ConfidenceState,
    val detectedFoodLabel: String?,
    val confidenceNotes: String?,
    val confirmationStatus: ConfirmationStatus,
    val source: FoodEntrySource,
    val entryStatus: FoodEntryStatus,
    val debugInteractionLog: String? = null,
    val deletedAt: Instant? = null,
)
