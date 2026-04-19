package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import java.io.File

class DietEntryInspectionService(
    private val foodEntryRepository: FoodEntryRepository,
    private val foodEstimationEngine: FoodEstimationEngine,
) {
    suspend fun inspectEntry(entryId: Long): Map<String, Any?> {
        val entry = foodEntryRepository.getEntry(entryId)
            ?: return mapOf(
                "success" to false,
                "message" to "Entry $entryId was not found.",
            )

        val imagePath = entry.imagePath.takeIf { it.isNotBlank() }
        val imageExists = imagePath?.let { File(it).exists() } == true
        val result = if (imageExists) {
            foodEstimationEngine.estimate(
                FoodEstimationRequest(
                    imagePath = imagePath!!,
                    capturedAtEpochMs = entry.capturedAt.toEpochMilli(),
                    preferredDescription = entry.detectedFoodLabel,
                    userContext = "Inspect saved meal entry $entryId",
                ),
            ).getOrNull()
        } else {
            null
        }

        return mapOf(
            "success" to true,
            "entryId" to entry.id,
            "date" to entry.entryDate.toString(),
            "description" to (entry.detectedFoodLabel ?: "Unknown meal"),
            "savedCalories" to entry.finalCalories,
            "estimatedCalories" to entry.estimatedCalories,
            "source" to entry.source.name,
            "needsManual" to (entry.entryStatus.name == "NeedsManual"),
            "hasPhoto" to imageExists,
            "photoReestimateCalories" to result?.estimatedCalories,
            "photoReestimateDescription" to result?.detectedFoodLabel,
            "photoReestimateConfidence" to result?.confidenceState?.name,
            "message" to if (imageExists) {
                "Inspected entry $entryId with its saved photo."
            } else {
                "Entry $entryId has no saved photo to inspect."
            },
        )
    }

    suspend fun reestimateEntry(
        entryId: Long,
        correctedDescription: String?,
        reason: String?,
    ): Map<String, Any?> {
        val entry = foodEntryRepository.getEntry(entryId)
            ?: return mapOf(
                "success" to false,
                "message" to "Entry $entryId was not found.",
            )

        val imagePath = entry.imagePath.takeIf { it.isNotBlank() }
            ?: return mapOf(
                "success" to false,
                "message" to "Entry $entryId has no saved photo to re-estimate.",
            )
        if (!File(imagePath).exists()) {
            return mapOf(
                "success" to false,
                "message" to "Entry $entryId has no readable saved photo to re-estimate.",
            )
        }

        val preferredDescription = correctedDescription?.trim()?.ifBlank { null } ?: entry.detectedFoodLabel
        val result = foodEstimationEngine.estimate(
            FoodEstimationRequest(
                imagePath = imagePath,
                capturedAtEpochMs = entry.capturedAt.toEpochMilli(),
                preferredDescription = preferredDescription,
                userContext = reason?.trim()?.ifBlank { null },
            ),
        ).getOrElse { error ->
            return mapOf(
                "success" to false,
                "message" to "I couldn't re-estimate entry $entryId right now.",
                "error" to (error.message ?: error::class.java.simpleName),
            )
        }

        val resolvedDescription = preferredDescription ?: result.detectedFoodLabel ?: entry.detectedFoodLabel ?: "Unknown meal"
        foodEntryRepository.upsert(
            entry.copy(
                estimatedCalories = result.estimatedCalories,
                finalCalories = result.estimatedCalories,
                confidenceState = result.confidenceState,
                detectedFoodLabel = resolvedDescription,
                confidenceNotes = result.confidenceNotes,
                confirmationStatus = ConfirmationStatus.NotRequired,
                source = FoodEntrySource.AiEstimate,
                entryStatus = FoodEntryStatus.Ready,
                debugInteractionLog = result.debugInteractionLog,
            ),
        )

        return mapOf(
            "success" to true,
            "entryId" to entry.id,
            "description" to resolvedDescription,
            "estimatedCalories" to result.estimatedCalories,
            "confidence" to result.confidenceState.name,
            "message" to "Re-estimated entry $entryId from its saved photo.",
        )
    }
}
