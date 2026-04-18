package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.data.preferences.AppPreferences

class SelectableDietChatEngine(
    private val preferences: AppPreferences,
    private val gemmaEngine: DietChatEngine,
) : DietChatEngine {
    override suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String> {
        val selected = preferences.readCoachModel()
        val engine = when (selected) {
            CoachModel.Gemma -> gemmaEngine
        }
        return engine.sendMessage(message, history, snapshot)
    }
}
