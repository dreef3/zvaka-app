package com.dreef3.weightlossapp.chat

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking

class DietEntryTools(
    private val snapshotProvider: () -> DietChatSnapshot,
    private val correctionService: DietEntryCorrectionService,
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
                    "entryId" to entry.entryId,
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
                    "entryId" to entry.entryId,
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

    @Tool(
        description = "Correct a saved food entry when the user explicitly provides better calories, a better description, or both. Use only when the target entry is clear.",
    )
    fun correctEntry(
        @ToolParam(description = "The exact entryId of the saved food entry to update.") entryId: Int,
        @ToolParam(description = "Corrected calorie value as a whole number. Use -1 if calories are not being changed.") correctedCalories: Int,
        @ToolParam(description = "Corrected short food description. Use empty string if description is not being changed.") correctedDescription: String,
        @ToolParam(description = "Short reason based on the user's correction.") reason: String,
    ): Map<String, Any?> = runBlocking {
        correctionService.applyCorrection(
            DietEntryCorrectionRequest(
                entryId = entryId.toLong(),
                correctedCalories = correctedCalories.takeIf { it >= 0 },
                correctedDescription = correctedDescription.takeIf { it.isNotBlank() },
                reason = reason,
            ),
        )
    }

    @Tool(
        description = "Log a new food entry without a photo when the user says what they ate and provides calories. Never ask for another description if the user already named the meal. Names like 'Mac Menu', 'potato burek', or 'yogurt with berries' are already complete meal names. Use today's date unless the user clearly specifies another ISO date.",
    )
    fun logFoodEntry(
        @ToolParam(description = "Exact meal name from the user's message. Copy it directly even if it is short or informal. Examples: 'Mac Menu', 'potato burek', 'yogurt with berries'.") mealName: String,
        @ToolParam(description = "Whole-number calories for the entry.") calories: Int,
        @ToolParam(description = "Entry date in ISO format yyyy-MM-dd. Use empty string for today.") dateIso: String,
        @ToolParam(description = "Short note explaining that this was added from chat. Do not ask the user for this.") reason: String,
    ): Map<String, Any?> = runBlocking {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "logFoodEntry mealName=$mealName calories=$calories dateIso=$dateIso")
        }
        correctionService.logEntry(
            DietEntryLogRequest(
                description = mealName,
                calories = calories,
                dateIso = dateIso.takeIf { it.isNotBlank() },
                reason = reason,
            ),
        )
    }

    companion object {
        private const val TAG = "DietEntryTools"
    }
}
