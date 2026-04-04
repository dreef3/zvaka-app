package com.dreef3.weightlossapp.chat

data class DietChatMessage(
    val role: ChatRole,
    val text: String,
)

enum class ChatRole {
    User,
    Assistant,
}

data class DietChatSnapshot(
    val todayBudgetCalories: Int?,
    val todayConsumedCalories: Int,
    val todayRemainingCalories: Int?,
    val entries: List<DietEntryContext>,
)

data class DietEntryContext(
    val entryId: Long,
    val dateIso: String,
    val description: String?,
    val finalCalories: Int,
    val estimatedCalories: Int,
    val needsManual: Boolean,
    val source: String,
)

interface DietChatEngine {
    suspend fun sendMessage(
        message: String,
        history: List<DietChatMessage>,
        snapshot: DietChatSnapshot,
    ): Result<String>
}
