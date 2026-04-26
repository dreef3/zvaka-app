package com.dreef3.weightlossapp.data.preferences

enum class GemmaBackend(
    val storageKey: String,
    val displayName: String,
) {
    CPU("cpu", "CPU"),
    GPU("gpu", "GPU"),
    NPU("npu", "NPU"),
    ;

    companion object {
        fun fromStorageKey(value: String): GemmaBackend? = entries.firstOrNull { it.storageKey == value }
    }
}
