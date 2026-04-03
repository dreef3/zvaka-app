package com.dreef3.weightlossapp.chat

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class DietEntryTools(
    private val snapshotProvider: () -> DietChatSnapshot,
) : ToolSet {

    @Tool(description = "Get the user's current calorie summary for today.")
    fun getTodaySummary(): Map<String, Any?> {
        val snapshot = snapshotProvider()
        return mapOf(
            "budgetCalories" to snapshot.todayBudgetCalories,
            "consumedCalories" to snapshot.todayConsumedCalories,
            "remainingCalories" to snapshot.todayRemainingCalories,
        )
    }

    @Tool(description = "Get the user's most recent food entries with descriptions and calories.")
    fun getRecentEntries(
        @ToolParam(description = "Maximum number of entries to return.") limit: Int,
    ): Map<String, Any> {
        val snapshot = snapshotProvider()
        return mapOf(
            "entries" to snapshot.entries.take(limit.coerceIn(1, 20)).map { entry ->
                mapOf(
                    "date" to entry.dateIso,
                    "description" to (entry.description ?: "Unknown meal"),
                    "calories" to entry.finalCalories,
                    "estimatedCalories" to entry.estimatedCalories,
                    "needsManual" to entry.needsManual,
                    "source" to entry.source,
                )
            },
        )
    }

    @Tool(description = "Search the user's food entries by food description text.")
    fun searchEntries(
        @ToolParam(description = "Text to match against saved food descriptions.") query: String,
        @ToolParam(description = "Maximum number of matching entries to return.") limit: Int,
    ): Map<String, Any> {
        val snapshot = snapshotProvider()
        val normalized = query.trim().lowercase()
        val matches = snapshot.entries
            .filter { entry -> (entry.description ?: "").lowercase().contains(normalized) }
            .take(limit.coerceIn(1, 20))

        return mapOf(
            "entries" to matches.map { entry ->
                mapOf(
                    "date" to entry.dateIso,
                    "description" to (entry.description ?: "Unknown meal"),
                    "calories" to entry.finalCalories,
                    "estimatedCalories" to entry.estimatedCalories,
                    "needsManual" to entry.needsManual,
                    "source" to entry.source,
                )
            },
        )
    }
}
