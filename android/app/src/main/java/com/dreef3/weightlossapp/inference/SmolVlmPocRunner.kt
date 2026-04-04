package com.dreef3.weightlossapp.inference

import android.content.Context
import android.util.Log
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.media.ModelStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmolVlmPocRunner(
    private val context: Context,
    private val modelStorage: ModelStorage,
) {
    suspend fun runSampleIfModelExists(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelFile = modelStorage.fileFor(ModelDescriptors.smolVlm)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw IllegalStateException("SmolVLM model missing at ${modelFile.absolutePath}")
            }

            val imageFile = File(context.filesDir, SAMPLE_IMAGE_FILE_NAME).also { target ->
                if (!target.exists()) {
                    context.assets.open(SAMPLE_ASSET_NAME).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            val result = SmolVlmFoodEstimationEngine(
                context = context,
                modelFile = modelFile,
            )
                .estimate(FoodEstimationRequest(imagePath = imageFile.absolutePath, capturedAtEpochMs = 0L))
                .getOrThrow()
            val summary = "calories=${result.estimatedCalories}, description=${result.detectedFoodLabel}"
            Log.i(TAG, "SmolVLM sample result=$summary")
            summary
        }.onFailure { error ->
            Log.w(TAG, "SmolVLM native sample failed", error)
        }
    }

    companion object {
        private const val TAG = "SmolVlmPocRunner"
        private const val SAMPLE_ASSET_NAME = "sample_spaghetti.jpg"
        private const val SAMPLE_IMAGE_FILE_NAME = "sample_spaghetti.jpg"
    }
}
