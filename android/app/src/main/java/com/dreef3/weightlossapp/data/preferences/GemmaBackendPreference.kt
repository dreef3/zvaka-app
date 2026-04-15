package com.dreef3.weightlossapp.data.preferences

import com.google.ai.edge.litertlm.Backend

data class GemmaBackendPreference(
    val mode: GemmaBackend,
    val backend: Backend,
) {
    val label: String
        get() = mode.storageKey
}
