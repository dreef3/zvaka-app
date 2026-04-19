package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import java.time.Instant
import java.time.LocalDate

data class DietEntryCorrectionRequest(
    val entryId: Long,
    val correctedCalories: Int?,
    val correctedDescription: String?,
    val reason: String?,
)

data class DietEntryLogRequest(
    val description: String,
    val calories: Int,
    val dateIso: String?,
    val reason: String?,
)

class DietEntryCorrectionService(
    private val foodEntryRepository: FoodEntryRepository,
    private val updateFoodEntryUseCase: UpdateFoodEntryUseCase,
    private val localDateProvider: LocalDateProvider,
) {
    suspend fun applyCorrection(request: DietEntryCorrectionRequest): Map<String, Any?> {
        require(request.correctedCalories != null || !request.correctedDescription.isNullOrBlank()) {
            "At least calories or description must be provided."
        }

        val existing = foodEntryRepository.getEntry(request.entryId)
            ?: return mapOf(
                "success" to false,
                "message" to "Entry ${request.entryId} was not found.",
            )

        val updated = existing.copy(
            finalCalories = request.correctedCalories ?: existing.finalCalories,
            detectedFoodLabel = request.correctedDescription?.trim()?.ifBlank { existing.detectedFoodLabel }
                ?: existing.detectedFoodLabel,
            source = FoodEntrySource.UserCorrected,
            confidenceNotes = buildCorrectionNote(existing.confidenceNotes, request.reason),
        )

        updateFoodEntryUseCase(updated)
        val persisted = foodEntryRepository.getEntry(request.entryId)
            ?: return mapOf(
                "success" to false,
                "message" to "Entry ${request.entryId} could not be reloaded after update.",
            )

        return mapOf(
            "success" to true,
            "entryId" to persisted.id,
            "description" to (persisted.detectedFoodLabel ?: "Unknown meal"),
            "finalCalories" to persisted.finalCalories,
            "message" to "Entry ${persisted.id} updated.",
        )
    }

    suspend fun logEntry(request: DietEntryLogRequest): Map<String, Any?> {
        require(request.description.isNotBlank()) { "Description must not be blank." }
        require(request.calories > 0) { "Calories must be greater than zero." }

        val entryDate = request.dateIso
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(LocalDate::parse)
            ?: localDateProvider.today()
        if (entryDate.isAfter(localDateProvider.today())) {
            return mapOf(
                "success" to false,
                "message" to "I can't log meals in the future.",
            )
        }
        val timestamp = Instant.now()

        val entry = FoodEntry(
            capturedAt = timestamp,
            entryDate = entryDate,
            imagePath = "",
            estimatedCalories = request.calories,
            finalCalories = request.calories,
            confidenceState = ConfidenceState.High,
            detectedFoodLabel = request.description.trim(),
            confidenceNotes = buildLogNote(request.reason),
            confirmationStatus = ConfirmationStatus.NotRequired,
            source = FoodEntrySource.UserCorrected,
            entryStatus = FoodEntryStatus.Ready,
        )

        val entryId = updateFoodEntryUseCase(entry)

        return mapOf(
            "success" to true,
            "entryId" to entryId.toInt(),
            "description" to request.description.trim(),
            "finalCalories" to request.calories,
            "date" to entryDate.toString(),
            "message" to "Logged ${request.description.trim()} at ${request.calories} kcal.",
        )
    }

    private fun buildCorrectionNote(existingNotes: String?, reason: String?): String? {
        val cleanedReason = reason?.trim()?.ifBlank { null }
        val correctionNote = cleanedReason?.let { "Coach correction: $it" } ?: "Coach correction applied."
        return listOfNotNull(existingNotes?.takeIf { it.isNotBlank() }, correctionNote).joinToString(" | ")
    }

    private fun buildLogNote(reason: String?): String? {
        val cleanedReason = reason?.trim()?.ifBlank { null }
        return cleanedReason?.let { "Coach logged entry: $it" } ?: "Coach logged entry."
    }
}
