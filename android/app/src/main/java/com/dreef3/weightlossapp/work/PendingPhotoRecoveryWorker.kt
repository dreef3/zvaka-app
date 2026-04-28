package com.dreef3.weightlossapp.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.app.media.PhotoStorage

class PendingPhotoRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val database = AppDatabase.build(appContext)
    private val photoStorage = PhotoStorage(appContext)
    private val scheduler = WorkManagerPhotoProcessingScheduler(appContext)

    override suspend fun doWork(): Result {
        val foodEntryDao = database.foodEntryDao()
        val processingEntries = foodEntryDao.getAll().filter { entry ->
            entry.deletedAtEpochMs == null && entry.entryStatus == FoodEntryStatus.Processing.name
        }

        processingEntries.forEach { entry ->
            if (photoStorage.isReadablePhoto(entry.imagePath)) {
                scheduler.enqueue(
                    entryId = entry.id,
                    imagePath = entry.imagePath,
                    capturedAtEpochMs = entry.capturedAtEpochMs,
                )
            } else {
                foodEntryDao.update(
                    entry.copy(
                        estimatedCalories = 0,
                        finalCalories = 0,
                        confidenceState = "Failed",
                        confidenceNotes = "The saved photo is no longer readable on this device, so background processing could not resume. Enter calories manually or recapture the meal photo.",
                        entryStatus = FoodEntryStatus.NeedsManual.name,
                    ),
                )
            }
        }

        return Result.success()
    }
}
