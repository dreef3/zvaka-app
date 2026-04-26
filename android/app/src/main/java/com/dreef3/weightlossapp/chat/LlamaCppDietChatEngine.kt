package com.dreef3.weightlossapp.chat

import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LlamaCppDietChatEngine(
    private val container: AppContainer,
    private val modelStorage: ModelStorage,
    private val modelDescriptor: ModelDescriptor,
    private val predictLength: Int = defaultPredictLength(modelDescriptor),
    private val generationTimeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
) : DietChatEngine {
    private val modelFile: File = modelStorage.fileFor(modelDescriptor)
    private val mutex = Mutex()
    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(container.appContext) }
    private var loaded = false

    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                error("Model unavailable")
            }
            ensureLoaded()
            withTimeout(generationTimeoutMs) {
                engine.sendUserPrompt(
                    message = buildPrompt(history, message, snapshot),
                    predictLength = predictLength,
                ).toList().joinToString("").trim()
            }
                .ifBlank { "I couldn't produce a useful answer yet. Please try again." }
        }.onFailure { Log.e(TAG, "sendMessage failed", it) }
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return
        engine.loadModel(modelFile.absolutePath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        loaded = true
    }

    private fun buildPrompt(
        history: List<DietChatMessage>,
        message: String,
        snapshot: DietChatSnapshot,
    ): String {
        val constrained = modelDescriptor.fileName.contains("Qwen", ignoreCase = true)
        val recentHistory = history.takeLast(if (constrained) 2 else 4).joinToString(separator = "\n") { item ->
            val role = if (item.role == ChatRole.User) "User" else "Coach"
            "$role: ${item.text.take(if (constrained) MAX_HISTORY_CHARS_CONSTRAINED else MAX_HISTORY_CHARS)}"
        }
        return buildString {
            if (recentHistory.isNotBlank()) {
                appendLine("Conversation so far:")
                appendLine(recentHistory)
                appendLine()
            }
            appendLine("Trusted user diet context:")
            appendLine(snapshot.buildSnapshotContext(constrained))
            appendLine()
            append("User: ")
            append(message.take(if (constrained) MAX_MESSAGE_CHARS_CONSTRAINED else MAX_MESSAGE_CHARS))
        }
    }

    private fun DietChatSnapshot.buildSnapshotContext(constrained: Boolean): String = buildString {
        appendLine("Today's budget calories: ${todayBudgetCalories ?: "unknown"}")
        appendLine("Today's consumed calories: $todayConsumedCalories")
        appendLine("Today's remaining calories: ${todayRemainingCalories ?: "unknown"}")
        appendLine("Recent food entries:")
        if (entries.isEmpty()) {
            appendLine("- none")
        } else {
            entries.take(if (constrained) 3 else 6).forEach { entry ->
                appendLine(
                    "- id=${entry.entryId}, ${entry.dateIso}: ${(entry.description ?: "Unknown meal").take(if (constrained) MAX_ENTRY_DESCRIPTION_CHARS_CONSTRAINED else MAX_ENTRY_DESCRIPTION_CHARS)}, " +
                        "${entry.finalCalories} kcal, source=${entry.source}, needsManual=${entry.needsManual}",
                )
            }
        }
    }

    companion object {
        private const val TAG = "LlamaCppDietChat"
        private const val DEFAULT_PREDICT_LENGTH = 192
        private const val CONSTRAINED_PREDICT_LENGTH = 64
        private const val DEFAULT_GENERATION_TIMEOUT_MS = 20_000L
        private const val MAX_HISTORY_CHARS = 220
        private const val MAX_HISTORY_CHARS_CONSTRAINED = 120
        private const val MAX_MESSAGE_CHARS = 600
        private const val MAX_MESSAGE_CHARS_CONSTRAINED = 180
        private const val MAX_ENTRY_DESCRIPTION_CHARS = 80
        private const val MAX_ENTRY_DESCRIPTION_CHARS_CONSTRAINED = 40
        private const val SYSTEM_PROMPT =
            "You are a concise, practical diet coach helping the user lose weight and build healthy, " +
                "sustainable eating habits. Prioritize protein, satiety, portion awareness, consistency, " +
                "and realistic next steps over perfection. Base claims on the trusted context. Keep answers " +
                "short and actionable. If the user asks to change saved entries, clearly explain the likely " +
                "calorie correction but do not pretend the change has already been applied."

        private fun defaultPredictLength(modelDescriptor: ModelDescriptor): Int =
            if (modelDescriptor.fileName.contains("Qwen", ignoreCase = true)) CONSTRAINED_PREDICT_LENGTH
            else DEFAULT_PREDICT_LENGTH
    }
}
