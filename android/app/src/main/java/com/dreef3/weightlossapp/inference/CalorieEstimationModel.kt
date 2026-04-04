package com.dreef3.weightlossapp.inference

enum class CalorieEstimationModel(
    val storageKey: String,
    val displayName: String,
) {
    Gemma("gemma", "Gemma"),
    SmolVlm("smolvlm", "SmolVLM");

    companion object {
        fun fromStorageKey(value: String?): CalorieEstimationModel =
            entries.firstOrNull { it.storageKey == value } ?: Gemma
    }
}
