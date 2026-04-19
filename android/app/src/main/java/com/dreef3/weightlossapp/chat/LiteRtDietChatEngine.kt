package com.dreef3.weightlossapp.chat

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.data.preferences.GemmaBackend
import com.google.ai.edge.litertlm.tool
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtDietChatEngine(
    private val modelFile: File,
    private val correctionService: DietEntryCorrectionService,
    private val inspectionService: DietEntryInspectionService,
    private val backendPreferenceProvider: suspend () -> GemmaBackend,
) : DietChatEngine {
    private val conversationRunner: CoachConversationRunner = LiteRtConversationRunner(
        modelFile = modelFile,
        backendPreferenceProvider = backendPreferenceProvider,
    )

    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            debugLog(
                "sendMessage start message=${message.take(160)} historyCount=${history.size} " +
                    "entryCount=${snapshot.entries.size} budget=${snapshot.todayBudgetCalories}",
            )
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw IllegalStateException("Model unavailable")
            }
            val tools = listOf(
                tool(
                    DietEntryTools(
                        snapshotProvider = { snapshot },
                        correctionService = correctionService,
                        inspectionService = inspectionService,
                    ),
                ),
            )
            debugLog("tools registered count=${tools.size}")
            val prompt = CoachPromptBuilder.buildPrompt(history, message, snapshot)
            debugLog("prompt built chars=${prompt.length}")
            conversationRunner.run(prompt, tools)
        }.onFailure { throwable ->
            Log.e(TAG, "sendMessage failed", throwable)
        }
    }

    companion object {
        private const val TAG = "LiteRtDietChat"
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

}
