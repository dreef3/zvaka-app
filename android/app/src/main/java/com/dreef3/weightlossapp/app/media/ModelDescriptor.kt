package com.dreef3.weightlossapp.app.media

data class ModelDescriptor(
    val fileName: String,
    val displayName: String,
    val url: String,
    val totalBytes: Long,
    val uniqueWorkName: String,
)

object ModelDescriptors {
    val gemma = ModelDescriptor(
        fileName = "gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        totalBytes = 2_583_085_056L,
        uniqueWorkName = "model-download-gemma",
    )
}
