package com.dreef3.weightlossapp.app.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ModelInvocationState(
    val activeLabel: String? = null,
    val waitingCount: Int = 0,
) {
    val isBusy: Boolean
        get() = activeLabel != null || waitingCount > 0
}

class ModelInvocationCoordinator {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ModelInvocationState())

    val state: StateFlow<ModelInvocationState> = _state.asStateFlow()

    suspend fun <T> runExclusive(
        label: String,
        block: suspend () -> T,
    ): T {
        if (currentCoroutineContext()[InvocationLease] != null) {
            return block()
        }

        _state.update { it.copy(waitingCount = it.waitingCount + 1) }
        return mutex.withLock {
            _state.update { current ->
                current.copy(
                    activeLabel = label,
                    waitingCount = (current.waitingCount - 1).coerceAtLeast(0),
                )
            }
            try {
                withContext(InvocationLease()) {
                    block()
                }
            } finally {
                _state.update { current ->
                    current.copy(activeLabel = null)
                }
            }
        }
    }

    private class InvocationLease : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InvocationLease>
    }
}
