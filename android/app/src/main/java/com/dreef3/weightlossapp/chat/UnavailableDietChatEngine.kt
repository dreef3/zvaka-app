package com.dreef3.weightlossapp.chat

class UnavailableDietChatEngine(
    private val modelName: String,
    private val reason: String,
) : DietChatEngine {
    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> = Result.success(
        "$modelName is selected, but $reason. Switch back to Gemma in Settings to keep using Coach.",
    )
}
