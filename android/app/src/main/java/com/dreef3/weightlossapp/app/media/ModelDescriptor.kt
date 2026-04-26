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

    val qwenCoach = ModelDescriptor(
        fileName = "Qwen3-0.6B.mediatek.mt6993.litertlm",
        displayName = "Qwen3 0.6B (MediaTek NPU)",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.mediatek.mt6993.litertlm?download=true",
        totalBytes = 992_000_000L,
        uniqueWorkName = "model-download-qwen-coach",
    )

    val gemma3Mt6989Coach = ModelDescriptor(
        fileName = "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm",
        displayName = "Gemma3 1B (MediaTek mt6989)",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm",
        totalBytes = 1_032_830_976L,
        uniqueWorkName = "model-download-gemma3-mt6989-coach",
    )
}
