package com.dreef3.weightlossapp.app.media

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ModelDownloadController {
    fun enqueueIfNeeded(model: ModelDescriptor)
    fun observeState(model: ModelDescriptor): Flow<ModelDownloadState>
}

class ModelDownloadRepository(
    private val context: Context,
    private val modelStorage: ModelStorage,
) : ModelDownloadController {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueIfNeeded(model: ModelDescriptor) {
        if (modelStorage.hasUsableModel(model)) return
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_URL, model.url)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, model.totalBytes)
                    .putString(ModelDownloadWorker.KEY_MODEL_NAME, model.displayName)
                    .putString(ModelDownloadWorker.KEY_MODEL_FILE_NAME, model.fileName)
                    .putString(ModelDownloadWorker.KEY_WORK_NAME, model.uniqueWorkName)
                    .build(),
            )
            .build()

        workManager.enqueueUniqueWork(
            model.uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun observeState(model: ModelDescriptor): Flow<ModelDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(model.uniqueWorkName)
            .map { infos ->
                if (modelStorage.hasUsableModel(model)) {
                    return@map ModelDownloadState(
                        isDownloading = false,
                        progressPercent = 100,
                        downloadedBytes = modelStorage.fileFor(model).length(),
                        totalBytes = modelStorage.fileFor(model).length().coerceAtLeast(model.totalBytes),
                    )
                }

                val info = infos.firstOrNull()
                if (info == null) {
                    return@map ModelDownloadState(totalBytes = model.totalBytes)
                }

                val downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_DOWNLOADED_BYTES, 0L)
                val totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_TOTAL_BYTES, model.totalBytes)
                val progressPercent = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt() else null
                when (info.state) {
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    -> ModelDownloadState(
                        isDownloading = true,
                        progressPercent = progressPercent,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    )

                    WorkInfo.State.SUCCEEDED -> ModelDownloadState(
                        isDownloading = false,
                        progressPercent = 100,
                        downloadedBytes = modelStorage.fileFor(model).length(),
                        totalBytes = totalBytes,
                    )

                    WorkInfo.State.FAILED -> ModelDownloadState(
                        isDownloading = false,
                        progressPercent = progressPercent,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        errorMessage = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                            ?: "Model download failed.",
                    )

                    WorkInfo.State.CANCELLED -> ModelDownloadState(
                        isDownloading = false,
                        progressPercent = progressPercent,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        errorMessage = "Model download cancelled.",
                    )
                }
            }
}
