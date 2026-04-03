package com.dreef3.weightlossapp.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
                val raw = activeEngine.createToolConversation(toolProviders).use { conversation ->
                    conversation.awaitResponse(bitmap = bitmap)
                }
                submittedEstimate?.toResult() ?: parseResponse(raw)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: FoodEstimationException) {
                throw exception
            } catch (_: Exception) {
                throw FoodEstimationException(FoodEstimationError.EstimationFailed)
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

    private suspend fun Conversation.awaitResponse(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val builder = StringBuilder()
            sendMessageAsync(
                Contents.of(
                    listOf(
                        Content.ImageBytes(bitmap.toPngByteArray()),
                        Content.Text(PROMPT),
                    ),
                ),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        builder.append(message.toString())
                    }

                    override fun onDone() {
                        if (continuation.isActive) {
                            continuation.resume(builder.toString())
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (!continuation.isActive) return
                        if (throwable is CancellationException) {
                            continuation.cancel(throwable)
                        } else {
                            continuation.resumeWithException(throwable)
                        }
                    }
                },
                emptyMap(),
            )

            continuation.invokeOnCancellation {
                runCatching { cancelProcess() }
            }
        }

    private fun parseResponse(raw: String): FoodEstimationResult {
        val descriptionMatch = Regex("""(?im)^description\s*:\s*(.+)$""").find(raw)
        val caloriesMatch = Regex("""(?im)^calories\s*:\s*(\d{1,5})$""").find(raw)
        if (caloriesMatch != null) {
            return FoodEstimationResult(
                estimatedCalories = caloriesMatch.groupValues[1].toInt().coerceAtLeast(0),
                confidenceState = ConfidenceState.High,
                detectedFoodLabel = descriptionMatch?.groupValues?.get(1)?.trim()?.ifBlank { null },
                confidenceNotes = null,
                detectedItems = descriptionMatch?.groupValues?.get(1)?.trim()?.let(::listOf) ?: emptyList(),
            )
        }

        val directCalories = Regex("""\b\d{1,5}\b""").find(raw)?.value?.toIntOrNull()
        if (directCalories != null) {
            return FoodEstimationResult(
                estimatedCalories = directCalories.coerceAtLeast(0),
                confidenceState = ConfidenceState.High,
                detectedFoodLabel = null,
                confidenceNotes = null,
                detectedItems = emptyList(),
            )
        }

        val values = raw.lineSequence()
            .mapNotNull { line ->
                val splitAt = line.indexOf('=')
                if (splitAt <= 0) {
                    null
                } else {
                    line.substring(0, splitAt).trim() to line.substring(splitAt + 1).trim()
                }
            }
            .toMap()

        val label = values["food"].orEmpty().ifBlank { "unknown meal" }
        val calories = values["calories"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val confidence = when (values["confidence"]?.lowercase()) {
            "high" -> ConfidenceState.High
            else -> ConfidenceState.NonHigh
        }

        return FoodEstimationResult(
            estimatedCalories = calories,
            confidenceState = confidence,
            detectedFoodLabel = label,
            confidenceNotes = values["notes"],
            detectedItems = listOf(label),
        )
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun ToolFoodEstimate.toResult(): FoodEstimationResult =
        FoodEstimationResult(
            estimatedCalories = calories,
            confidenceState = ConfidenceState.High,
            detectedFoodLabel = description.ifBlank { null },
            confidenceNotes = null,
            detectedItems = description.ifBlank { null }?.let(::listOf) ?: emptyList(),
        )

    companion object {
        private const val TAG = "LiteRtFoodEngine"
        private const val DEFAULT_MAX_TOKENS = 4000
        private const val DEFAULT_TOP_K = 64
        private const val DEFAULT_TOP_P = 0.95
        private const val DEFAULT_TEMPERATURE = 1.0
        private const val PROMPT = """
            Estimate the food in this photo.
            Output exactly two lines and no extra text.
            description: one short line describing the food
            calories: single integer estimated calories
            It is ok if this is just a guess.
        """
    }
}

class FoodEstimationException(
    val error: FoodEstimationError,
) : IllegalStateException(error.toString())
