package com.dreef3.weightlossapp.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.dreef3.weightlossapp.R
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.widget.HomeStatusWidgetUpdater
import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.DietChatSnapshot
import com.dreef3.weightlossapp.chat.DietEntryContext
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.inference.FoodEstimationError
import com.dreef3.weightlossapp.inference.FoodEstimationException
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

class PersistentEngineTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override suspend fun doWork(): Result {
        createChannelIfNeeded()
        setForeground(createForegroundInfo())
        AppContainer.initialize(applicationContext)
        val container = AppContainer.instance

        return try {
            runCatching {
                when (inputData.getString(KEY_TASK_TYPE)) {
                    TASK_TYPE_PHOTO_ESTIMATE -> processPhotoEstimate(container)
                    TASK_TYPE_CHAT_REPLY -> processChatReply(container)
                    else -> Result.failure()
                }
            }.getOrElse { throwable ->
                if (shouldRetryAfterFailure(throwable)) {
                    Result.retry()
                } else {
                    Log.e(TAG, "Persistent engine worker failed", throwable)
                    Result.failure()
                }
            }
        } finally {
            HomeStatusWidgetUpdater.requestRefresh(applicationContext)
        }
    }

    private suspend fun processPhotoEstimate(container: AppContainer): Result {
        val entryId = inputData.getLong(KEY_ENTRY_ID, 0L)
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return Result.failure()
        val capturedAtEpochMs = inputData.getLong(KEY_CAPTURED_AT_EPOCH_MS, 0L)
        if (entryId == 0L || capturedAtEpochMs == 0L) return Result.failure()

        val existingEntry = container.foodEntryRepository.getEntry(entryId) ?: return Result.success()
        val sessionId = inputData.getLong(KEY_SESSION_ID, 0L).takeIf { it != 0L }
        val userVisibleText = inputData.getString(KEY_USER_VISIBLE_TEXT)
        val preferredDescription = inputData.getString(KEY_PREFERRED_DESCRIPTION)?.trim()?.ifBlank { null }

        val estimationResult = container.liteRtMutex.withLock {
            container.foodEstimationEngine.estimate(
                FoodEstimationRequest(
                    imagePath = imagePath,
                    capturedAtEpochMs = capturedAtEpochMs,
                    userContext = userVisibleText?.takeIf { it.isNotBlank() },
                    preferredDescription = preferredDescription,
                ),
            )
        }
        estimationResult.exceptionOrNull()?.let { throwable ->
            if (!shouldRetryAfterFailure(throwable)) {
                recordWorkerException(
                    throwable = throwable,
                    entryId = entryId,
                    imagePath = imagePath,
                    stage = "photo_estimate",
                )
            }
        }
        if (shouldRetryAfterFailure(estimationResult.exceptionOrNull())) {
            return Result.retry()
        }

        val updatedEntry = estimationResult.fold(
            onSuccess = { estimation ->
                val detectedLabel = preferredDescription ?: estimation.detectedFoodLabel
                FoodEntry(
                    id = entryId,
                    capturedAt = existingEntry.capturedAt,
                    entryDate = existingEntry.entryDate,
                    imagePath = imagePath,
                    estimatedCalories = estimation.estimatedCalories,
                    finalCalories = estimation.estimatedCalories,
                    confidenceState = estimation.confidenceState,
                    detectedFoodLabel = detectedLabel,
                    confidenceNotes = estimation.confidenceNotes,
                    confirmationStatus = ConfirmationStatus.NotRequired,
                    source = FoodEntrySource.AiEstimate,
                    entryStatus = FoodEntryStatus.Ready,
                    debugInteractionLog = estimation.debugInteractionLog,
                    deletedAt = existingEntry.deletedAt,
                    modelImprovementUploadedAt = existingEntry.modelImprovementUploadedAt,
                )
            },
            onFailure = { throwable ->
                existingEntry.copy(
                    estimatedCalories = 0,
                    finalCalories = 0,
                    confidenceState = ConfidenceState.Failed,
                    detectedFoodLabel = preferredDescription ?: existingEntry.detectedFoodLabel,
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

        container.foodEntryRepository.upsert(updatedEntry)
        if (updatedEntry.entryStatus == FoodEntryStatus.Ready) {
            runCatching {
                container.modelImprovementUploader.uploadIfEnabled(updatedEntry)
            }.onFailure { throwable ->
                Log.e(TAG, "Model improvement upload failed for entryId=$entryId", throwable)
                container.modelImprovementUploadScheduler.enqueueImmediateSync()
                recordWorkerException(
                    throwable = throwable,
                    entryId = entryId,
                    imagePath = imagePath,
                    stage = "model_improvement_upload",
                )
            }
        }

        if (sessionId != null) {
            val reply = estimationResult.fold(
                onSuccess = { result ->
                    if (result.confidenceState == ConfidenceState.High) {
                        val description = preferredDescription ?: result.detectedFoodLabel ?: "Meal photo"
                        "Logged $description at ${result.estimatedCalories} kcal."
                    } else {
                        "I saved the photo, but automatic estimation wasn't reliable enough. Open Today and enter calories manually."
                    }
                },
                onFailure = {
                    "I saved the photo, but automatic estimation wasn't reliable enough. Open Today and enter calories manually."
                },
            )
            appendAssistantReply(container, sessionId, userVisibleText ?: "Attached a food photo for calorie estimate.", reply)
        }

        return Result.success()
    }

    private suspend fun processChatReply(container: AppContainer): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, 0L)
        val triggerMessageId = inputData.getLong(KEY_TRIGGER_MESSAGE_ID, 0L)
        val actualPrompt = inputData.getString(KEY_ACTUAL_PROMPT) ?: return Result.failure()
        val userVisibleText = inputData.getString(KEY_USER_VISIBLE_TEXT) ?: actualPrompt
        if (sessionId == 0L || triggerMessageId == 0L) return Result.failure()

        val history = container.coachChatRepository.getMessages(sessionId)
            .filter { it.id <= triggerMessageId }
        val snapshot = buildSnapshot(container)
        val response = container.liteRtMutex.withLock {
            runCatching {
                container.dietChatEngine.sendMessage(
                    message = actualPrompt,
                    history = history,
                    snapshot = snapshot,
                ).getOrElse {
                    Log.e(TAG, "dietChatEngine returned failure", it)
                    "I couldn't answer that yet. Try again in a moment."
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "Queued chat reply failed", throwable)
                "I hit an internal error while answering. Please try again."
            }
        }

        appendAssistantReply(container, sessionId, userVisibleText, response)
        return Result.success()
    }

    private suspend fun appendAssistantReply(
        container: AppContainer,
        sessionId: Long,
        userVisibleText: String,
        reply: String,
    ) {
        container.coachChatRepository.appendMessage(
            sessionId = sessionId,
            role = ChatRole.Assistant,
            text = reply,
            createdAtEpochMs = System.currentTimeMillis(),
            imagePath = null,
        )
        container.coachChatRepository.updateSessionSummary(sessionId, summarizeConversation(reply, userVisibleText))
    }

    private suspend fun buildSnapshot(container: AppContainer): DietChatSnapshot {
        val today = container.localDateProvider.today()
        val entries = container.foodEntryRepository.observeAllEntries().first()
        val budget = container.profileRepository.findBudgetFor(today)?.caloriesPerDay
        val todayEntries = entries.filter { entry ->
            entry.entryDate == today &&
                entry.deletedAt == null &&
                entry.confirmationStatus != ConfirmationStatus.Rejected &&
                entry.entryStatus == FoodEntryStatus.Ready
        }
        return DietChatSnapshot(
            todayBudgetCalories = budget,
            todayConsumedCalories = todayEntries.sumOf { it.finalCalories },
            todayRemainingCalories = budget?.minus(todayEntries.sumOf { it.finalCalories }),
            entries = entries
                .filter { it.deletedAt == null && it.confirmationStatus != ConfirmationStatus.Rejected }
                .sortedByDescending { it.capturedAt }
                .map { entry ->
                    DietEntryContext(
                        entryId = entry.id,
                        dateIso = entry.entryDate.toString(),
                        description = entry.detectedFoodLabel,
                        finalCalories = entry.finalCalories,
                        estimatedCalories = entry.estimatedCalories,
                        needsManual = entry.entryStatus == FoodEntryStatus.NeedsManual,
                        source = entry.source.name,
                    )
                },
        )
    }

    private fun summarizeConversation(assistantText: String, userText: String?): String {
        val assistantLine = assistantText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val userLine = userText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return assistantLine?.take(120)
            ?: userLine?.take(120)
            ?: "Coach conversation"
    }

    private fun recordWorkerException(
        throwable: Throwable,
        entryId: Long,
        imagePath: String,
        stage: String,
    ) {
        crashlytics.setCustomKey("engine_task_entry_id", entryId)
        crashlytics.setCustomKey("engine_task_stage", stage)
        crashlytics.setCustomKey("engine_task_image_path", imagePath)
        crashlytics.recordException(throwable)
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Working through queued AI tasks")
            .setContentText("Processing chat replies and meal photos in order.")
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
            "Queued AI tasks",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun shouldRetryAfterFailure(throwable: Throwable?): Boolean =
        throwable is CancellationException ||
            throwable is InterruptedException ||
            (throwable is FoodEstimationException && throwable.error == FoodEstimationError.InferenceTimeout)

    companion object {
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_CAPTURED_AT_EPOCH_MS = "captured_at_epoch_ms"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_USER_VISIBLE_TEXT = "user_visible_text"
        const val KEY_PREFERRED_DESCRIPTION = "preferred_description"
        const val KEY_TRIGGER_MESSAGE_ID = "trigger_message_id"
        const val KEY_ACTUAL_PROMPT = "actual_prompt"

        const val TASK_TYPE_PHOTO_ESTIMATE = "photo_estimate"
        const val TASK_TYPE_CHAT_REPLY = "chat_reply"

        private const val TAG = "PersistentEngineTask"
        private const val CHANNEL_ID = "persistent_engine_tasks"
        private const val NOTIFICATION_ID = 1004
    }
}
