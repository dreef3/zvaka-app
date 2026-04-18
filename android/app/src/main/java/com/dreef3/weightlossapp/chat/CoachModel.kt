package com.dreef3.weightlossapp.chat

enum class CoachModel(
    val storageKey: String,
    val displayName: String,
) {
    Gemma("gemma", "Gemma");

    companion object {
        fun fromStorageKey(value: String?): CoachModel =
            entries.firstOrNull { it.storageKey == value } ?: Gemma
    }
}
