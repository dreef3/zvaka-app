package com.dreef3.weightlossapp.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dreef3.weightlossapp.domain.usecase.EngineQueueState
import com.dreef3.weightlossapp.domain.usecase.EngineTaskQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class WorkManagerEngineTaskQueue(
    private val context: Context,
) : EngineTaskQueue {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueuePhotoEstimate(
        entryId: Long,
        imagePath: String,
        capturedAtEpochMs: Long,
        sessionId: Long?,
        userVisibleText: String?,
        preferredDescription: String?,
    ) {
        enqueue(
            uniqueWorkName = UNIQUE_PHOTO_WORK_NAME,
            inputData = Data.Builder()
                .putString(PersistentEngineTaskWorker.KEY_TASK_TYPE, PersistentEngineTaskWorker.TASK_TYPE_PHOTO_ESTIMATE)
                .putLong(PersistentEngineTaskWorker.KEY_ENTRY_ID, entryId)
                .putString(PersistentEngineTaskWorker.KEY_IMAGE_PATH, imagePath)
                .putLong(PersistentEngineTaskWorker.KEY_CAPTURED_AT_EPOCH_MS, capturedAtEpochMs)
                .putLong(PersistentEngineTaskWorker.KEY_SESSION_ID, sessionId ?: 0L)
                .putString(PersistentEngineTaskWorker.KEY_USER_VISIBLE_TEXT, userVisibleText)
                .putString(PersistentEngineTaskWorker.KEY_PREFERRED_DESCRIPTION, preferredDescription)
                .build(),
            tags = buildSet {
                add(TAG_ENGINE_QUEUE)
                add(TAG_PHOTO_ESTIMATE)
                add(entryTag(entryId))
                sessionId?.let { add(sessionTag(it)) }
            },
        )
    }

    override fun enqueueChatReply(
        sessionId: Long,
        triggerMessageId: Long,
        userVisibleText: String,
        actualPrompt: String,
    ) {
        enqueue(
            uniqueWorkName = UNIQUE_CHAT_WORK_NAME,
            inputData = Data.Builder()
                .putString(PersistentEngineTaskWorker.KEY_TASK_TYPE, PersistentEngineTaskWorker.TASK_TYPE_CHAT_REPLY)
                .putLong(PersistentEngineTaskWorker.KEY_SESSION_ID, sessionId)
                .putLong(PersistentEngineTaskWorker.KEY_TRIGGER_MESSAGE_ID, triggerMessageId)
                .putString(PersistentEngineTaskWorker.KEY_USER_VISIBLE_TEXT, userVisibleText)
                .putString(PersistentEngineTaskWorker.KEY_ACTUAL_PROMPT, actualPrompt)
                .build(),
            tags = setOf(
                TAG_ENGINE_QUEUE,
                TAG_CHAT_REPLY,
                sessionTag(sessionId),
            ),
        )
    }

    override fun observeState(sessionId: Long?): Flow<EngineQueueState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_PHOTO_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_CHAT_WORK_NAME),
        ) { photoInfos, chatInfos ->
            val activeInfos = (photoInfos + chatInfos).filter { it.state in ACTIVE_STATES }
            EngineQueueState(
                totalPendingCount = activeInfos.size,
                photoPendingCount = activeInfos.count { TAG_PHOTO_ESTIMATE in it.tags },
                chatPendingCount = activeInfos.count { TAG_CHAT_REPLY in it.tags },
                sessionPendingCount = sessionId?.let { id ->
                    activeInfos.count { sessionTag(id) in it.tags }
                } ?: 0,
            )
        }

    private fun enqueue(uniqueWorkName: String, inputData: Data, tags: Set<String>) {
        val request = OneTimeWorkRequestBuilder<PersistentEngineTaskWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply {
                tags.forEach(::addTag)
            }
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private companion object {
        private const val UNIQUE_PHOTO_WORK_NAME = "engine-photo-queue"
        private const val UNIQUE_CHAT_WORK_NAME = "engine-chat-queue"
        private const val TAG_ENGINE_QUEUE = "engine-queue"
        private const val TAG_PHOTO_ESTIMATE = "engine-photo-estimate"
        private const val TAG_CHAT_REPLY = "engine-chat-reply"
        private val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )

        private fun sessionTag(sessionId: Long): String = "engine-session-$sessionId"

        private fun entryTag(entryId: Long): String = "engine-entry-$entryId"
    }
}
