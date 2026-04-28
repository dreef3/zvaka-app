package com.dreef3.weightlossapp.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object PendingPhotoRecoveryScheduler {
    private const val UNIQUE_WORK_NAME = "pending-photo-recovery"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<PendingPhotoRecoveryWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
