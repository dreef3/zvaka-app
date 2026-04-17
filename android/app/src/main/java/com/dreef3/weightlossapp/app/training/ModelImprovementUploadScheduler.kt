package com.dreef3.weightlossapp.app.training

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dreef3.weightlossapp.work.ModelImprovementUploadWorker
import java.util.concurrent.TimeUnit

class ModelImprovementUploadScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<ModelImprovementUploadWorker>()
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enablePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<ModelImprovementUploadWorker>(12, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disablePeriodicSync() {
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private companion object {
        const val IMMEDIATE_WORK_NAME = "model-improvement-upload-immediate"
        const val PERIODIC_WORK_NAME = "model-improvement-upload-periodic"
    }
}
