package com.dreef3.weightlossapp.app.media

data class ModelDescriptor(
    val fileName: String,
    val displayName: String,
    val url: String,
    val totalBytes: Long,
    val uniqueWorkName: String,
)

object ModelDescriptors {
    val smolVlm = ModelDescriptor(
        fileName = "smalvlm-256m-instruct_q8_ekv2048_single_image.tflite",
        displayName = "SmolVLM 256M",
        url = "https://huggingface.co/litert-community/SmolVLM-256M-Instruct/resolve/main/smalvlm-256m-instruct_q8_ekv2048_single_image.tflite?download=true",
        totalBytes = 289_111_595L,
        uniqueWorkName = "model-download-smolvlm",
    )

    val gemma = ModelDescriptor(
        fileName = "gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        totalBytes = 2_583_085_056L,
        uniqueWorkName = "model-download-gemma",
    )
}
