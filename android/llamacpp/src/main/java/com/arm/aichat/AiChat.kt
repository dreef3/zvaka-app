package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl
import com.arm.aichat.internal.MultimodalEngineImpl

/**
 * Main entry point for Arm's AI Chat library.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)

    fun getMultimodalEngine(context: Context) = MultimodalEngineImpl.getInstance(context)
}
