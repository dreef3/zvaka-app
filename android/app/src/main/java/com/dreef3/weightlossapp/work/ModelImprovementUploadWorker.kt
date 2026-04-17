package com.dreef3.weightlossapp.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreef3.weightlossapp.app.di.AppContainer

class ModelImprovementUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppContainer.initialize(applicationContext)
        val container = AppContainer.instance
        return runCatching {
            container.modelImprovementUploader.uploadPendingIfEnabled()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
