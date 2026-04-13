package com.dreef3.weightlossapp.chat

enum class CoachModel(
    val storageKey: String,
    val displayName: String,
) {
    Gemma("gemma", "Gemma"),
    SmolLm("smollm", "SmolLM3"),
    SmolLm2("smollm2", "SmolLM2"),
    Qwen0_8b("qwen-0.8b", "Qwen 0.8B"),
    Qwen2b("qwen-2b", "Qwen 2B");

    companion object {
        fun fromStorageKey(value: String?): CoachModel =
            entries.firstOrNull { it.storageKey == value } ?: Gemma
    }
}
