package com.dreef3.weightlossapp.chat

object CoachPromptBuilder {
    fun buildPrompt(
        history: List<DietChatMessage>,
        message: String,
        snapshot: DietChatSnapshot,
    ): String {
        val recentHistory = history.takeLast(8).joinToString(separator = "\n") { item ->
            val role = if (item.role == ChatRole.User) "User" else "Coach"
            "$role: ${item.text}"
        }
        return buildString {
            if (recentHistory.isNotBlank()) {
                appendLine("Conversation so far:")
                appendLine(recentHistory)
                appendLine()
            }
            appendLine("Trusted user diet context:")
            appendLine(snapshot.buildSnapshotContext())
            appendLine()
            append("User: ")
            append(message)
        }
    }

    private fun DietChatSnapshot.buildSnapshotContext(): String = buildString {
        appendLine("Today's budget calories: ${todayBudgetCalories ?: "unknown"}")
        appendLine("Today's consumed calories: $todayConsumedCalories")
        appendLine("Today's remaining calories: ${todayRemainingCalories ?: "unknown"}")
        appendLine("Recent food entries:")
        if (entries.isEmpty()) {
            appendLine("- none")
        } else {
            entries.take(12).forEach { entry ->
                appendLine(
                    "- id=${entry.entryId}, ${entry.dateIso}: ${entry.description ?: "Unknown meal"}, " +
                        "${entry.finalCalories} kcal, source=${entry.source}, needsManual=${entry.needsManual}",
                )
            }
        }
    }
}
