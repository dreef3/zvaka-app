package com.dreef3.weightlossapp.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.dreef3.weightlossapp.R
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.inference.FoodEstimationError
import com.dreef3.weightlossapp.inference.FoodEstimationException
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import java.time.Instant
import java.util.concurrent.CancellationException

class PhotoProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(KEY_ENTRY_ID, 0L)
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return Result.failure()
        val capturedAtEpochMs = inputData.getLong(KEY_CAPTURED_AT_EPOCH_MS, 0L)
        return runCatching {
            if (entryId == 0L || capturedAtEpochMs == 0L) {
                return Result.failure()
            }

            createChannelIfNeeded()
            setForeground(createForegroundInfo())

            AppContainer.initialize(applicationContext)
            val container = AppContainer.instance
            val capturedAt = Instant.ofEpochMilli(capturedAtEpochMs)
            val entryDate = container.localDateProvider.dateFor(capturedAt)

            val result = container.foodEstimationEngine.estimate(
                FoodEstimationRequest(
                    imagePath = imagePath,
                    capturedAtEpochMs = capturedAtEpochMs,
                ),
            )
            result.exceptionOrNull()?.let { throwable ->
                if (!shouldRetryAfterFailure(throwable)) {
                    recordWorkerException(
                        throwable = throwable,
                        entryId = entryId,
                        imagePath = imagePath,
                        stage = "estimate",
                    )
                }
            }
            if (shouldRetryAfterFailure(result.exceptionOrNull())) {
                return Result.retry()
            }

            val entry = result.fold(
                onSuccess = { estimation ->
                    FoodEntry(
                        id = entryId,
                        capturedAt = capturedAt,
                        entryDate = entryDate,
                        imagePath = imagePath,
                        estimatedCalories = estimation.estimatedCalories,
                        finalCalories = estimation.estimatedCalories,
                        confidenceState = estimation.confidenceState,
                        detectedFoodLabel = estimation.detectedFoodLabel,
                        confidenceNotes = estimation.confidenceNotes,
                        confirmationStatus = ConfirmationStatus.NotRequired,
                        source = FoodEntrySource.AiEstimate,
                        entryStatus = FoodEntryStatus.Ready,
                        debugInteractionLog = estimation.debugInteractionLog,
                    )
                },
                onFailure = { throwable ->
                    FoodEntry(
                        id = entryId,
                        capturedAt = capturedAt,
                        entryDate = entryDate,
                        imagePath = imagePath,
                        estimatedCalories = 0,
                        finalCalories = 0,
                        confidenceState = ConfidenceState.Failed,
                        detectedFoodLabel = null,
                        confidenceNotes = "Photo saved. Automatic estimate was not reliable enough, so calories need manual entry.",
                        confirmationStatus = ConfirmationStatus.NotRequired,
                        source = FoodEntrySource.AiEstimate,
                        entryStatus = FoodEntryStatus.NeedsManual,
                        debugInteractionLog = when (throwable) {
                            is FoodEstimationException -> throwable.debugInteractionLog
                            else -> throwable.stackTraceToString()
                        },
                    )
                },
            )

            container.foodEntryRepository.upsert(entry)
            if (entry.entryStatus == FoodEntryStatus.Ready) {
                runCatching {
                    container.modelImprovementUploader.uploadIfEnabled(entry)
                }.onFailure { throwable ->
                    recordWorkerException(
                        throwable = throwable,
                        entryId = entryId,
                        imagePath = imagePath,
                        stage = "model_improvement_upload",
                    )
                }
            }
            Result.success()
        }.getOrElse { throwable ->
            if (shouldRetryAfterFailure(throwable)) {
                Result.retry()
            } else {
                recordWorkerException(
                    throwable = throwable,
                    entryId = entryId,
                    imagePath = imagePath,
                    stage = "worker_boundary",
                )
                Result.failure()
            }
        }
    }

    private fun recordWorkerException(
        throwable: Throwable,
        entryId: Long,
        imagePath: String,
        stage: String,
    ) {
        crashlytics.setCustomKey("photo_worker_entry_id", entryId)
        crashlytics.setCustomKey("photo_worker_stage", stage)
        crashlytics.setCustomKey("photo_worker_image_path", imagePath)
        crashlytics.recordException(throwable)
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Estimating calories")
            .setContentText("Processing your food photo in the background.")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Photo estimation",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_CAPTURED_AT_EPOCH_MS = "captured_at_epoch_ms"

        private const val CHANNEL_ID = "photo_estimation"
        private const val NOTIFICATION_ID = 1002

        internal fun shouldRetryAfterFailure(throwable: Throwable?): Boolean =
            throwable is CancellationException ||
                throwable is InterruptedException ||
                (throwable is FoodEstimationException && throwable.error == FoodEstimationError.InferenceTimeout)
    }
}
