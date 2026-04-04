package com.dreef3.weightlossapp.app.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.dreef3.weightlossapp.R

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val modelStorage = ModelStorage(appContext)
    private val modelDownloader = ModelDownloader(modelStorage)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        createChannelIfNeeded()
        val modelFileName = inputData.getString(KEY_MODEL_FILE_NAME) ?: ModelDescriptors.gemma.fileName
        val url = inputData.getString(KEY_MODEL_URL) ?: ModelDescriptors.gemma.url
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, ModelDescriptors.gemma.totalBytes)
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: ModelDescriptors.gemma.displayName
        val descriptor = ModelDescriptor(
            fileName = modelFileName,
            displayName = modelName,
            url = url,
            totalBytes = totalBytes,
            uniqueWorkName = inputData.getString(KEY_WORK_NAME) ?: ModelDescriptors.gemma.uniqueWorkName,
        )

        setForeground(createForegroundInfo(modelName = modelName, progressPercent = 0))

        val result = modelDownloader.downloadFrom(
            model = descriptor,
        ) { downloadedBytes, expectedBytes ->
            val progressPercent = if (expectedBytes > 0L) ((downloadedBytes * 100L) / expectedBytes).toInt() else 0
            setProgressAsync(
                Data.Builder()
                    .putLong(KEY_PROGRESS_DOWNLOADED_BYTES, downloadedBytes)
                    .putLong(KEY_PROGRESS_TOTAL_BYTES, expectedBytes)
                    .build(),
            )
            setForegroundAsync(createForegroundInfo(modelName = modelName, progressPercent = progressPercent))
        }

        return result.fold(
            onSuccess = {
                Result.success(
                    Data.Builder()
                        .putLong(KEY_PROGRESS_DOWNLOADED_BYTES, it.length())
                        .putLong(KEY_PROGRESS_TOTAL_BYTES, totalBytes)
                        .build(),
                )
            },
            onFailure = {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, it.message ?: "Model download failed.")
                        .putLong(KEY_PROGRESS_TOTAL_BYTES, totalBytes)
                        .build(),
                )
            },
        )
    }

    private fun createForegroundInfo(
        modelName: String,
        progressPercent: Int,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading $modelName")
            .setContentText("Downloading on device: $progressPercent%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_MODEL_URL = "model_url"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_MODEL_FILE_NAME = "model_file_name"
        const val KEY_WORK_NAME = "work_name"
        const val KEY_PROGRESS_DOWNLOADED_BYTES = "progress_downloaded_bytes"
        const val KEY_PROGRESS_TOTAL_BYTES = "progress_total_bytes"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 1001
    }
}
