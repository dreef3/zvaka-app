package com.dreef3.weightlossapp.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.domain.model.ConfidenceState
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
import com.google.ai.edge.litertlm.tool
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LiteRtFoodEstimationEngine(
    private val modelFile: File,
) : FoodEstimationEngine {
    private val engineMutex = Mutex()
    private var engine: Engine? = null

    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        withContext(Dispatchers.Default) {
            runCatching {
            logModelLookup("estimate-start")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.e(TAG, "Model file missing at ${modelFile.absolutePath}")
                throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
            }

            val bitmap = BitmapFactory.decodeFile(request.imagePath)
                ?: throw FoodEstimationException(FoodEstimationError.UnreadableImage)

            val estimationResult = try {
                val activeEngine = getOrCreateEngine()
                var submittedEstimate: ToolFoodEstimate? = null
                val toolProviders = listOf(
                    tool(
                        FoodEstimationTools { estimate ->
                            submittedEstimate = estimate
                        },
                    ),
                )
                debugLog("estimate prompt chars=${PROMPT.trimIndent().length} tools=${toolProviders.size}")
                val response = activeEngine.createToolConversation(toolProviders).use { conversation ->
                    conversation.awaitResponse(bitmap = bitmap)
                }
                debugLog("estimate raw final=${response.responseText.take(400)}")
                submittedEstimate?.let {
                    debugLog("tool result description=${it.description} calories=${it.calories}")
                }
                submittedEstimate?.toResult(response.debugTrace) ?: FoodEstimationTextParser
                    .parse(response.responseText)
                    .copy(debugInteractionLog = response.debugTrace)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: FoodEstimationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "estimate failed", exception)
                throw FoodEstimationException(
                    error = FoodEstimationError.EstimationFailed,
                    debugInteractionLog = exception.stackTraceToString(),
                )
            }

            estimationResult
            }
        }

    @OptIn(ExperimentalApi::class)
    private fun Engine.createToolConversation(toolProviders: List<com.google.ai.edge.litertlm.ToolProvider>): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        return try {
            createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = DEFAULT_TOP_K,
                        topP = DEFAULT_TOP_P,
                        temperature = DEFAULT_TEMPERATURE,
                    ),
                    systemInstruction = Contents.of(
                        listOf(
                            Content.Text(
                                """
                                You analyze a food photo and must call submitFoodEstimate exactly once.
                                Reason carefully about what the food is and its portion size before deciding calories,
                                but do that reasoning internally.
                                The description must be one short line.
                                Calories must be a single integer estimate.
                                Do not answer in free-form text when the tool is available.
                                """.trimIndent(),
                            ),
                        ),
                    ),
                    tools = toolProviders,
                ),
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override suspend fun warmUp(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            logModelLookup("warmup-start")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
            }
            getOrCreateEngine()
            Unit
        }
    }

    private suspend fun getOrCreateEngine(): Engine = engineMutex.withLock {
        engine?.let { return it }

        logModelLookup("engine-init")
        val attempts = listOf(
            "gpu" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = null,
                maxNumTokens = DEFAULT_MAX_TOKENS,
                cacheDir = null,
            ),
            "cpu" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.GPU(),
                audioBackend = null,
                maxNumTokens = DEFAULT_MAX_TOKENS,
                cacheDir = null,
            ),
        )

        var lastException: Exception? = null
        for ((label, config) in attempts) {
            try {
                Log.i(TAG, "Initializing LiteRT-LM engine with backend=$label")
                return Engine(config).also { created ->
                    created.initialize()
                    engine = created
                    Log.i(TAG, "LiteRT-LM engine initialized with backend=$label for ${modelFile.absolutePath}")
                }
            } catch (exception: Exception) {
                lastException = exception
                Log.e(TAG, "LiteRT-LM engine initialization failed for backend=$label", exception)
            }
        }
        throw FoodEstimationException(FoodEstimationError.ModelLoadFailed).also {
            if (lastException != null) {
                Log.e(TAG, "All LiteRT-LM engine initialization attempts failed", lastException)
            }
        }
    }

    private fun logModelLookup(stage: String) {
        Log.i(
            TAG,
            "stage=$stage path=${modelFile.absolutePath} exists=${modelFile.exists()} canRead=${modelFile.canRead()} length=${modelFile.length()} parent=${modelFile.parentFile?.absolutePath}",
        )
        val children = modelFile.parentFile?.listFiles()
            ?.joinToString(prefix = "[", postfix = "]") { file ->
                "${file.name}(exists=${file.exists()},size=${file.length()})"
            }
            ?: "null"
        Log.i(TAG, "stage=$stage parentChildren=$children")
    }

    private suspend fun Conversation.awaitResponse(bitmap: Bitmap): ConversationTrace =
        suspendCancellableCoroutine { continuation ->
            val builder = StringBuilder()
            val trace = StringBuilder()
            var lastChannel: String? = null
            trace.appendLine("Prompt:")
            trace.appendLine(PROMPT.trimIndent())
            trace.appendLine()
            sendMessageAsync(
                Contents.of(
                    listOf(
                        Content.ImageBytes(bitmap.toPngByteArray()),
                        Content.Text(PROMPT),
                    ),
                ),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        message.channels["thought"]?.takeIf { it.isNotBlank() }?.let { thought ->
                            debugLog("thought chunk=${thought.take(500)}")
                            if (lastChannel != "thought") {
                                if (trace.isNotEmpty() && !trace.endsWith("\n\n")) {
                                    trace.appendLine()
                                }
                                trace.appendLine("[thought]")
                                lastChannel = "thought"
                            }
                            trace.appendLine(thought)
                        }
                        debugLog("text chunk=${message.toString().take(500)}")
                        if (lastChannel != "text") {
                            if (trace.isNotEmpty() && !trace.endsWith("\n\n")) {
                                trace.appendLine()
                            }
                            trace.appendLine("[text]")
                            lastChannel = "text"
                        }
                        trace.appendLine(message.toString())
                        builder.append(message.toString())
                    }

                    override fun onDone() {
                        debugLog("onDone totalLength=${builder.length}")
                        if (continuation.isActive) {
                            continuation.resume(
                                ConversationTrace(
                                    responseText = builder.toString(),
                                    debugTrace = trace.toString(),
                                ),
                            )
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

            continuation.invokeOnCancellation {
                runCatching { cancelProcess() }
            }
    }

    private data class ConversationTrace(
        val responseText: String,
        val debugTrace: String,
    )

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun ToolFoodEstimate.toResult(rawTrace: String): FoodEstimationResult =
        FoodEstimationResult(
            estimatedCalories = calories,
            confidenceState = ConfidenceState.High,
            detectedFoodLabel = description.ifBlank { null },
            confidenceNotes = null,
            detectedItems = description.ifBlank { null }?.let(::listOf) ?: emptyList(),
            debugInteractionLog = buildString {
                appendLine(rawTrace.trimEnd())
                appendLine()
                appendLine("[tool]")
                appendLine("Description: $description")
                appendLine("Calories: $calories")
            },
        )

    companion object {
        private const val TAG = "LiteRtFoodEngine"
        private const val DEFAULT_MAX_TOKENS = 4000
        private const val DEFAULT_TOP_K = 64
        private const val DEFAULT_TOP_P = 0.95
        private const val DEFAULT_TEMPERATURE = 1.0
        private const val PROMPT = """
            Look carefully at this food photo and estimate calories.
            Think about the ingredients and portion size before answering, but do not show that reasoning.
            Output exactly two lines and no extra text.
            First line must start with Description:
            Second line must start with Calories:
        """
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}

class FoodEstimationException(
    val error: FoodEstimationError,
    val debugInteractionLog: String? = null,
) : IllegalStateException(error.toString())
