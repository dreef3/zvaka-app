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
    fun enqueueIfNeeded()
    fun observeState(): Flow<ModelDownloadState>
}

class ModelDownloadRepository(
    private val context: Context,
    private val modelStorage: ModelStorage,
) : ModelDownloadController {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueIfNeeded() {
        if (modelStorage.hasUsableModel()) return
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_URL, ModelDownloadConfig.MODEL_URL)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, ModelDownloadConfig.MODEL_TOTAL_BYTES)
                    .putString(ModelDownloadWorker.KEY_MODEL_NAME, ModelDownloadConfig.MODEL_DISPLAY_NAME)
                    .build(),
            )
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadConfig.MODEL_DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun observeState(): Flow<ModelDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadConfig.MODEL_DOWNLOAD_WORK_NAME)
            .map { infos ->
                if (modelStorage.hasUsableModel()) {
                    return@map ModelDownloadState(
                        isDownloading = false,
                        progressPercent = 100,
                        downloadedBytes = modelStorage.defaultModelFile.length(),
                        totalBytes = modelStorage.defaultModelFile.length().coerceAtLeast(ModelDownloadConfig.MODEL_TOTAL_BYTES),
                    )
                }

                val info = infos.firstOrNull()
                if (info == null) {
                    return@map ModelDownloadState()
                }

                val downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_DOWNLOADED_BYTES, 0L)
                val totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_TOTAL_BYTES, ModelDownloadConfig.MODEL_TOTAL_BYTES)
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
                        downloadedBytes = modelStorage.defaultModelFile.length(),
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
