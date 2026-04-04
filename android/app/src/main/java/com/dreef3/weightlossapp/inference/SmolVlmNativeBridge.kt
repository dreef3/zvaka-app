package com.dreef3.weightlossapp.inference

object SmolVlmNativeBridge {
    init {
        System.loadLibrary("smolvlm_poc")
    }

    external fun runSamplePoc(
        modelPath: String,
        imagePath: String,
        prompt: String,
    ): String
}
