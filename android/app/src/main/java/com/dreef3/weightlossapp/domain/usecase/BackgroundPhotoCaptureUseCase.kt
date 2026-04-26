package com.dreef3.weightlossapp.domain.usecase

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.app.media.PhotoStorage
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import java.time.Instant

class BackgroundPhotoCaptureUseCase(
    private val repository: FoodEntryRepository,
    private val scheduler: PhotoProcessingScheduler,
    private val localDateProvider: LocalDateProvider,
    private val photoStorage: PhotoStorage,
) {
    suspend fun enqueue(imagePath: String, capturedAt: Instant): Long {
        require(photoStorage.isReadablePhoto(imagePath)) {
            "Captured photo is not readable: $imagePath"
        }
        photoStorage.normalizePhoto(imagePath)
        val pendingEntry = FoodEntry(
            capturedAt = capturedAt,
            entryDate = localDateProvider.dateFor(capturedAt),
            imagePath = imagePath,
            estimatedCalories = 0,
            finalCalories = 0,
            confidenceState = ConfidenceState.Failed,
            detectedFoodLabel = null,
            confidenceNotes = "Processing photo in background.",
            confirmationStatus = ConfirmationStatus.NotRequired,
            source = FoodEntrySource.AiEstimate,
            entryStatus = FoodEntryStatus.Processing,
        )
        val entryId = repository.upsert(pendingEntry)
        scheduler.enqueue(
            entryId = entryId,
            imagePath = imagePath,
            capturedAtEpochMs = capturedAt.toEpochMilli(),
        )
        return entryId
    }

    suspend fun retry(entry: FoodEntry) {
        if (!photoStorage.isReadablePhoto(entry.imagePath)) {
            repository.upsert(
                entry.copy(
                    estimatedCalories = 0,
                    finalCalories = 0,
                    confidenceState = ConfidenceState.Failed,
                    detectedFoodLabel = entry.detectedFoodLabel,
                    confidenceNotes = "The saved photo is no longer readable on this device, so retry cannot run. Enter calories manually or recapture the meal photo.",
                    confirmationStatus = ConfirmationStatus.NotRequired,
                    source = FoodEntrySource.AiEstimate,
                    entryStatus = FoodEntryStatus.NeedsManual,
                ),
            )
            return
        }
        photoStorage.normalizePhoto(entry.imagePath)
        if (!photoStorage.isReadablePhoto(entry.imagePath)) {
            repository.upsert(
                entry.copy(
                    estimatedCalories = 0,
                    finalCalories = 0,
                    confidenceState = ConfidenceState.Failed,
                    detectedFoodLabel = entry.detectedFoodLabel,
                    confidenceNotes = "The saved photo became unreadable during preparation, so retry cannot run. Enter calories manually or recapture the meal photo.",
                    confirmationStatus = ConfirmationStatus.NotRequired,
                    source = FoodEntrySource.AiEstimate,
                    entryStatus = FoodEntryStatus.NeedsManual,
                ),
            )
            return
        }
        val retryingEntry = entry.copy(
            estimatedCalories = 0,
            finalCalories = 0,
            confidenceState = ConfidenceState.Failed,
            detectedFoodLabel = null,
            confidenceNotes = "Retrying photo estimation in background.",
            confirmationStatus = ConfirmationStatus.NotRequired,
            source = FoodEntrySource.AiEstimate,
            entryStatus = FoodEntryStatus.Processing,
        )
        repository.upsert(retryingEntry)
        scheduler.enqueue(
            entryId = entry.id,
            imagePath = entry.imagePath,
            capturedAtEpochMs = entry.capturedAt.toEpochMilli(),
        )
    }
}
