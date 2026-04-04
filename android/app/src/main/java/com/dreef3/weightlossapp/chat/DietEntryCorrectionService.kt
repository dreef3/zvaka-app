package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase

data class DietEntryCorrectionRequest(
    val entryId: Long,
    val correctedCalories: Int?,
    val correctedDescription: String?,
    val reason: String?,
)

class DietEntryCorrectionService(
    private val foodEntryRepository: FoodEntryRepository,
    private val updateFoodEntryUseCase: UpdateFoodEntryUseCase,
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

        return mapOf(
            "success" to true,
            "entryId" to updated.id,
            "description" to (updated.detectedFoodLabel ?: "Unknown meal"),
            "finalCalories" to updated.finalCalories,
            "message" to "Entry ${updated.id} updated.",
        )
    }

    private fun buildCorrectionNote(existingNotes: String?, reason: String?): String? {
        val cleanedReason = reason?.trim()?.ifBlank { null }
        val correctionNote = cleanedReason?.let { "Coach correction: $it" } ?: "Coach correction applied."
        return listOfNotNull(existingNotes?.takeIf { it.isNotBlank() }, correctionNote).joinToString(" | ")
    }
}
