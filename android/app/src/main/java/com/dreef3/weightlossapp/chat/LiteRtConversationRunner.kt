package com.dreef3.weightlossapp.chat

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.data.preferences.GemmaBackend
import com.dreef3.weightlossapp.data.preferences.GemmaBackendPreference
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
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface CoachConversationRunner {
    suspend fun run(prompt: String, tools: List<ToolProvider>): String
}

class LiteRtConversationRunner(
    private val modelFile: File,
    private val backendPreferenceProvider: suspend () -> GemmaBackend,
) : CoachConversationRunner {
    private val engineMutex = Mutex()
    private var engine: Engine? = null
    private var enginePreference: GemmaBackendPreference? = null

    override suspend fun run(prompt: String, tools: List<ToolProvider>): String = withContext(Dispatchers.Default) {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw IllegalStateException("Model unavailable")
        }
        val activeEngine = getOrCreateEngine()
        activeEngine.createConversationWithTools(tools).use { conversation ->
            conversation.awaitTextResponse(prompt).trim().ifBlank {
                "I couldn't produce a useful answer yet. Please try again."
            }
        }
    }

    private suspend fun getOrCreateEngine(): Engine = engineMutex.withLock {
        val desiredPreference = backendPreferenceProvider().toEnginePreference()
        engine?.takeIf { enginePreference?.mode == desiredPreference.mode }?.let { return it }
        if (engine != null && enginePreference?.mode != desiredPreference.mode) {
            runCatching { engine?.close() }
                .onFailure { Log.w(TAG, "Failed closing LiteRT coach engine during backend switch", it) }
            engine = null
            enginePreference = null
        }
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = desiredPreference.backend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = 4000,
            cacheDir = null,
        )
        return Engine(engineConfig).also { created ->
            created.initialize()
            engine = created
            enginePreference = desiredPreference
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
                            Content.Text(LiteRtSystemInstructions.text),
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
                        debugLog("text chunk=${message.toString().take(800)}")
                        builder.append(message.toString())
                    }

                    override fun onDone() {
                        if (continuation.isActive) continuation.resume(builder.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        if (!continuation.isActive) return
                        if (throwable is CancellationException) continuation.cancel(throwable)
                        else continuation.resumeWithException(throwable)
                    }
                },
                mapOf("enable_thinking" to "true"),
            )
        }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun GemmaBackend.toEnginePreference(): GemmaBackendPreference = when (this) {
        GemmaBackend.CPU -> GemmaBackendPreference(mode = this, backend = Backend.CPU())
        GemmaBackend.GPU -> GemmaBackendPreference(mode = this, backend = Backend.GPU())
    }

    private companion object {
        const val TAG = "LiteRtConversationRunner"
    }
}

object LiteRtSystemInstructions {
    val text: String =
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
        in text. If calories are missing or ambiguous, first attempt to
        estimate them using the meal description and the trusted context
        (recent entries, typical portion sizes, etc.). Do not ask the
        user for calories up-front; only ask a brief clarifying
        question if a reasonable estimate cannot be made or the
        confidence would be low.
        The meal name in the user's own message is already a complete
        value for logFoodEntry.mealName. Do not ask for a separate,
        clearer, or more specific description when the user already named
        the meal, for example 'Mac Menu', 'potato burek', or
        'yogurt with berries'.
        If the user gives both a meal name and calories, call
        logFoodEntry immediately.
        Never use logFoodEntry for requests about an already saved meal,
        such as correcting, changing, renaming, updating, re-estimating,
        or re-checking the last/latest/existing entry.
        If you need more detail about one saved entry, first use
        inspectEntry with that entryId. It can return a fresh photo-based
        estimate for that exact saved meal when a photo exists.
        For requests to re-estimate, re-check, or rename a saved entry and
        then estimate it again, use getRecentEntries and/or searchEntries to
        identify the right entryId, then call reestimateEntry. Do not rely on
        the exact wording being in English.
        Never create or suggest entries with future dates.
        If the user asks you to estimate calories for a named meal in text,
        estimate them first, then call logFoodEntry with that same
        user-provided meal name. When the user mentions an unlogged meal
        without providing calories, assume they want it logged and try an
        automatic estimation before asking for numbers.
        If you need to save the meal, prefer tools over plain text.
        Base concrete claims about the user's diet on the trusted context and
        tool results, not guesses.
        Always be specific about today's meals first, then give 1-3 useful
        suggestions or cautions. Keep answers short and actionable.
        """.trimIndent()
}
