package com.dreef3.weightlossapp.inference

enum class CalorieEstimationModel(
    val storageKey: String,
    val displayName: String,
) {
    Gemma("gemma", "Gemma"),
    SmolVlm("smolvlm", "SmolVLM"),
    Qwen0_8b("qwen-0.8b", "Qwen 0.8B"),
    Qwen2b("qwen-2b", "Qwen 2B");

    companion object {
        fun fromStorageKey(value: String?): CalorieEstimationModel =
            entries.firstOrNull { it.storageKey == value } ?: Gemma
    }
}
