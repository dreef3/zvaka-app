package com.dreef3.weightlossapp.chat

enum class CoachModel(
    val storageKey: String,
    val displayName: String,
) {
    Gemma("gemma", "Gemma"),
    GemmaGguf("gemma_gguf", "Gemma GGUF"),
    Qwen("qwen", "Qwen"),
    Gemma3Mt6989("gemma3_mt6989", "Gemma3 mt6989");

    companion object {
        fun fromStorageKey(value: String?): CoachModel =
            entries.firstOrNull { it.storageKey == value } ?: Gemma
    }
}
