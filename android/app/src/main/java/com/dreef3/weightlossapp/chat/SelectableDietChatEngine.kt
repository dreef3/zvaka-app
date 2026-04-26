package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.data.preferences.AppPreferences

class SelectableDietChatEngine(
    private val preferences: AppPreferences,
    private val gemmaEngine: DietChatEngine,
    private val gemmaGgufEngine: DietChatEngine,
    private val qwenEngine: DietChatEngine,
    private val gemma3Mt6989Engine: DietChatEngine,
) : DietChatEngine {
    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> {
        val selected = preferences.readCoachModel()
        val engine = when (selected) {
            CoachModel.Gemma -> gemmaEngine
            CoachModel.GemmaGguf -> gemmaGgufEngine
            CoachModel.Qwen -> qwenEngine
            CoachModel.Gemma3Mt6989 -> gemma3Mt6989Engine
        }
        return engine.sendMessage(message, history, snapshot)
    }
}
