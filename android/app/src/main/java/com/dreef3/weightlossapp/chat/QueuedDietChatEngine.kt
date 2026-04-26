package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.model.ModelInvocationCoordinator

class QueuedDietChatEngine(
    private val delegate: DietChatEngine,
    private val coordinator: ModelInvocationCoordinator,
    private val label: String,
) : DietChatEngine {
    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> = coordinator.runExclusive(label) {
        delegate.sendMessage(message, history, snapshot)
    }
}
