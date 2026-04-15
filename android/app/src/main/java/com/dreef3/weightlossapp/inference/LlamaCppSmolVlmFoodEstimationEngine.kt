package com.dreef3.weightlossapp.inference

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.MultimodalEngine
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamaCppSmolVlmFoodEstimationEngine(
    context: Context,
    private val modelStorage: ModelStorage,
    private val modelFile: File = modelStorage.fileFor(ModelDescriptors.smolVlm),
    private val mmprojFile: File = modelStorage.fileFor(ModelDescriptors.smolVlmMmproj),
) : FoodEstimationEngine {
    private val mutex = Mutex()
    private val engine: MultimodalEngine by lazy { AiChat.getMultimodalEngine(context) }
    private var loaded = false

    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                if (!modelFile.exists() || modelFile.length() == 0L || !mmprojFile.exists() || mmprojFile.length() == 0L) {
                    throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
                }
                Log.i(
                    TAG,
                    "estimate imagePath=${request.imagePath} imageExists=${File(request.imagePath).exists()} imageBytes=${File(request.imagePath).length()} modelBytes=${modelFile.length()} mmprojBytes=${mmprojFile.length()}",
                )
                ensureLoaded()
                val response = engine.analyzeImage(
                    prompt = buildPrompt(request),
                    imagePath = request.imagePath,
                    predictLength = SAFE_PREDICT_LENGTH,
                )
                Log.i(TAG, "analyzeImage responseLength=${response.length} responsePreview=${response.take(200)}")
                if (response.isBlank()) {
                    throw FoodEstimationException(FoodEstimationError.EstimationFailed)
                }
                FoodEstimationTextParser.parse(response)
            }.recoverCatching { error ->
                if (error is FoodEstimationException) throw error
                Log.e(TAG, "SmolVLM llama.cpp estimation failed", error)
                throw FoodEstimationException(FoodEstimationError.EstimationFailed)
            }
        }

    override suspend fun warmUp(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            if (!modelFile.exists() || !mmprojFile.exists()) {
                throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
            }
            ensureLoaded()
            Unit
        }
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return
        Log.i(
            TAG,
            "Loading SmolVLM model=${modelFile.absolutePath} modelBytes=${modelFile.length()} mmproj=${mmprojFile.absolutePath} mmprojBytes=${mmprojFile.length()}",
        )
        engine.loadModel(modelFile.absolutePath, mmprojFile.absolutePath)
        loaded = true
    }

    private fun buildPrompt(request: FoodEstimationRequest): String = buildString {
        appendLine("Estimate the food calories from this photo.")
        appendLine("Think carefully about the visible food items and portion size before answering.")
        appendLine("Reply with exactly these fields on separate lines:")
        appendLine("description: <short meal description>")
        appendLine("calories: <integer>")
        appendLine("The description must name the actual food, for example 'fried rice with chicken' or 'burger and fries'.")
        appendLine("Do not use generic descriptions like 'meal', 'food', 'dish', 'lunch', or 'dinner'.")
        request.preferredDescription?.takeIf { it.isNotBlank() }?.let {
            appendLine("Use this meal hint if it matches the image: $it")
        }
        request.userContext?.takeIf { it.isNotBlank() }?.let {
            appendLine("User context: $it")
        }
        appendLine("Do not add extra commentary.")
    }

    companion object {
        private const val TAG = "LlamaCppSmolVlm"
        private const val SAFE_PREDICT_LENGTH = 48
    }
}
