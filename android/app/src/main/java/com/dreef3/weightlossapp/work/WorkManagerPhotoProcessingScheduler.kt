package com.dreef3.weightlossapp.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.dreef3.weightlossapp.domain.usecase.PhotoProcessingScheduler

class WorkManagerPhotoProcessingScheduler(
    private val context: Context,
) : PhotoProcessingScheduler {
    override fun enqueue(
        entryId: Long,
        imagePath: String,
        capturedAtEpochMs: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<PhotoProcessingWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(PhotoProcessingWorker.KEY_ENTRY_ID, entryId)
                    .putString(PhotoProcessingWorker.KEY_IMAGE_PATH, imagePath)
                    .putLong(PhotoProcessingWorker.KEY_CAPTURED_AT_EPOCH_MS, capturedAtEpochMs)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_PREFIX + entryId,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        private const val UNIQUE_WORK_PREFIX = "photo-processing-"
    }
}
