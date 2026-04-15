package com.arm.aichat

interface MultimodalEngine {
    suspend fun loadModel(modelPath: String, mmprojPath: String)

    suspend fun analyzeImage(
        prompt: String,
        imagePath: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
    ): String

    fun cleanUp()

    fun destroy()

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 256
    }
}
