package com.dreef3.weightlossapp.chat

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LiteRtDietChatEngine(
    private val modelFile: File,
    private val correctionService: DietEntryCorrectionService,
) : DietChatEngine {
    private val engineMutex = Mutex()
    private var engine: Engine? = null

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
            val activeEngine = getOrCreateEngine()
            val tools = listOf(
                tool(
                    DietEntryTools(
                        snapshotProvider = { snapshot },
                        correctionService = correctionService,
                    ),
                ),
            )
            debugLog("tools registered count=${tools.size}")
            val prompt = buildPrompt(history, message, snapshot)
            debugLog("prompt built chars=${prompt.length}")
            activeEngine.createConversationWithTools(tools).use { conversation ->
                conversation.awaitTextResponse(prompt).trim().ifBlank {
                    "I couldn't produce a useful answer yet. Please try again."
                }
            }
        }.onFailure { throwable ->
            Log.e(TAG, "sendMessage failed", throwable)
        }
    }

    private suspend fun getOrCreateEngine(): Engine = engineMutex.withLock {
        engine?.let { return it }
        debugLog("initializing engine modelPath=${modelFile.absolutePath} size=${modelFile.length()}")
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = 4000,
            cacheDir = null,
        )
        return Engine(engineConfig).also { created ->
            created.initialize()
            engine = created
            debugLog("engine initialized")
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun Engine.createConversationWithTools(tools: List<ToolProvider>): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        return try {
            createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 64,
                        topP = 0.95,
                        temperature = 0.8,
                    ),
                    systemInstruction = Contents.of(
                        listOf(
                            Content.Text(
                                """
                                You are a concise, practical diet coach helping the user lose weight
                                and build healthy, sustainable eating habits.
                                Prioritize protein, satiety, portion awareness, consistency, and
                                realistic next steps over perfection.
                                Use the available tools when advice depends on the user's logged meals
                                or calorie history.
                                When the user corrects a saved meal, use tools to find the entry and
                                call correctEntry instead of merely suggesting the change.
                                When the user tells you about an unlogged meal and gives enough
                                information to save it, call logFoodEntry instead of only replying
                                in text. If calories are missing or ambiguous, ask a short follow-up
                                question instead of guessing.
                                The meal name in the user's own message is already a complete
                                value for logFoodEntry.mealName. Do not ask for a separate,
                                clearer, or more specific description when the user already named
                                the meal, for example 'Mac Menu', 'potato burek', or
                                'yogurt with berries'.
                                If the user gives both a meal name and calories, call
                                logFoodEntry immediately.
                                If the user asks you to estimate calories for a named meal in text,
                                estimate them first, then call logFoodEntry with that same
                                user-provided meal name. Do not ask for another description.
                                If you need to save the meal, prefer tools over plain text.
                                Examples:
                                User: "I had Mac Menu, 950 kcal."
                                Assistant action: call logFoodEntry(mealName="Mac Menu", calories=950, ...)
                                User: "I ate potato burek, estimate calories for me."
                                Assistant action: estimate calories, then call logFoodEntry(mealName="potato burek", ...)
                                User: "Log yogurt with berries for yesterday, 220 kcal."
                                Assistant action: call logFoodEntry(mealName="yogurt with berries", dateIso="<yesterday>", calories=220, ...)
                                Base concrete claims about the user's diet on the trusted context and
                                tool results, not guesses.
                                Always be specific about today's meals first, then give 1-3 useful
                                suggestions or cautions. Keep answers short and actionable.
                                """.trimIndent(),
                            ),
                        ),
                    ),
                    tools = tools,
                ),
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    private suspend fun Conversation.awaitTextResponse(prompt: String): String =
        suspendCancellableCoroutine { continuation ->
            val builder = StringBuilder()
            sendMessageAsync(
                Contents.of(listOf(Content.Text(prompt))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        message.channels["thought"]?.takeIf { it.isNotBlank() }?.let { thought ->
                            debugLog("thought chunk=${thought.take(800)}")
                        }
                        debugLog("onMessage chunkLength=${message.toString().length}")
                        debugLog("text chunk=${message.toString().take(800)}")
                        builder.append(message.toString())
                    }

                    override fun onDone() {
                        debugLog("onDone totalLength=${builder.length}")
                        if (continuation.isActive) {
                            continuation.resume(builder.toString())
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "conversation onError", throwable)
                        if (!continuation.isActive) return
                        if (throwable is CancellationException) {
                            continuation.cancel(throwable)
                        } else {
                            continuation.resumeWithException(throwable)
                        }
                    }
                },
                mapOf("enable_thinking" to "true"),
            )
        }

    private fun buildPrompt(
        history: List<DietChatMessage>,
        message: String,
        snapshot: DietChatSnapshot,
    ): String {
        val recentHistory = history.takeLast(8).joinToString(separator = "\n") { item ->
            val role = if (item.role == ChatRole.User) "User" else "Coach"
            "$role: ${item.text}"
        }
        return buildString {
        if (recentHistory.isNotBlank()) {
            appendLine("Conversation so far:")
            appendLine(recentHistory)
            appendLine()
        }
        appendLine("Trusted user diet context:")
        appendLine(snapshot.buildSnapshotContext())
        appendLine()
        append("User: ")
        append(message)
        }
    }

    private fun DietChatSnapshot.buildSnapshotContext(): String = buildString {
        appendLine("Today's budget calories: ${todayBudgetCalories ?: "unknown"}")
        appendLine("Today's consumed calories: $todayConsumedCalories")
        appendLine("Today's remaining calories: ${todayRemainingCalories ?: "unknown"}")
        appendLine("Recent food entries:")
        if (entries.isEmpty()) {
            appendLine("- none")
        } else {
            entries.take(12).forEach { entry ->
                appendLine(
                    "- id=${entry.entryId}, ${entry.dateIso}: ${entry.description ?: "Unknown meal"}, " +
                        "${entry.finalCalories} kcal, source=${entry.source}, needsManual=${entry.needsManual}",
                )
            }
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
