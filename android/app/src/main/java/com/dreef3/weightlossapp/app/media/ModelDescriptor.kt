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

    val gemmaGgufCoach = ModelDescriptor(
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        displayName = "Gemma 4 E2B GGUF",
        url = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf?download=true",
        totalBytes = 3_110_000_000L,
        uniqueWorkName = "model-download-gemma-gguf-coach",
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

    val smolVlmTflite = ModelDescriptor(
        fileName = "smalvlm-256m-instruct_q8_ekv2048_single_image.tflite",
        displayName = "SmolVLM 256M",
        url = "https://huggingface.co/litert-community/SmolVLM-256M-Instruct/resolve/main/smalvlm-256m-instruct_q8_ekv2048_single_image.tflite?download=true",
        totalBytes = 289_111_595L,
        uniqueWorkName = "model-download-smolvlm-tflite",
    )

    val smolLm = ModelDescriptor(
        fileName = "SmolLM3-Q4_K_M.gguf",
        displayName = "SmolLM3 3B",
        url = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q4_K_M.gguf?download=true",
        totalBytes = 1_924_563_328L,
        uniqueWorkName = "model-download-smollm-gguf",
    )

    val smolLm2 = ModelDescriptor(
        fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
        displayName = "SmolLM2 1.7B",
        url = "https://huggingface.co/unsloth/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf?download=true",
        totalBytes = 1_055_609_504L,
        uniqueWorkName = "model-download-smollm2-gguf",
    )

    val qwen0_8b = ModelDescriptor(
        fileName = "Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
        displayName = "Qwen 3.5 0.8B",
        url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-UD-Q4_K_XL.gguf?download=true",
        totalBytes = 558_772_480L,
        uniqueWorkName = "model-download-qwen-0.8b-gguf",
    )

    val qwen0_8bMmproj = ModelDescriptor(
        fileName = "mmproj-Qwen3.5-0.8B-F16.gguf",
        displayName = "Qwen 3.5 0.8B mmproj",
        url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf?download=true",
        totalBytes = 204_987_232L,
        uniqueWorkName = "model-download-qwen-0.8b-mmproj",
    )

    val qwen2b = ModelDescriptor(
        fileName = "Qwen3.5-2B-UD-Q4_K_XL.gguf",
        displayName = "Qwen 3.5 2B",
        url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q4_K_XL.gguf?download=true",
        totalBytes = 1_339_752_704L,
        uniqueWorkName = "model-download-qwen-2b-gguf",
    )

    val qwen2bMmproj = ModelDescriptor(
        fileName = "mmproj-Qwen3.5-2B-F16.gguf",
        displayName = "Qwen 3.5 2B mmproj",
        url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf?download=true",
        totalBytes = 668_227_264L,
        uniqueWorkName = "model-download-qwen-2b-mmproj",
    )

    val smolVlm = ModelDescriptor(
        fileName = "SmolVLM-256M-Instruct-Q8_0.gguf",
        displayName = "SmolVLM 256M GGUF",
        url = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/SmolVLM-256M-Instruct-Q8_0.gguf?download=true",
        totalBytes = 183_500_800L,
        uniqueWorkName = "model-download-smolvlm-gguf",
    )

    val smolVlmMmproj = ModelDescriptor(
        fileName = "mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",
        displayName = "SmolVLM mmproj GGUF",
        url = "https://huggingface.co/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf?download=true",
        totalBytes = 109_051_904L,
        uniqueWorkName = "model-download-smolvlm-mmproj",
    )

    val all = listOf(
        gemma,
        gemmaGgufCoach,
        qwenCoach,
        gemma3Mt6989Coach,
        smolVlmTflite,
        smolLm,
        smolLm2,
        qwen0_8b,
        qwen0_8bMmproj,
        qwen2b,
        qwen2bMmproj,
        smolVlm,
        smolVlmMmproj,
    )
}
