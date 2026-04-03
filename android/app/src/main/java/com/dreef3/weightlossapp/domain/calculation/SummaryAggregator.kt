package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.DailySummary
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import java.time.LocalDate

class SummaryAggregator {
    fun buildSummary(
        date: LocalDate,
        budgetCalories: Int,
        entries: List<FoodEntry>,
    ): DailySummary {
        val activeEntries = entries.filter {
            it.deletedAt == null &&
                it.confirmationStatus != ConfirmationStatus.Rejected &&
                it.entryStatus == FoodEntryStatus.Ready
        }
        val consumed = activeEntries.sumOf { it.finalCalories }
        val remaining = budgetCalories - consumed
        return DailySummary(
            date = date,
            budgetCalories = budgetCalories,
            consumedCalories = consumed,
            remainingCalories = remaining,
            status = when {
                remaining > 0 -> "under"
                remaining < 0 -> "over"
                else -> "at"
            },
            entryCount = activeEntries.size,
            hasLimitedConfidenceEntries = activeEntries.any { it.confidenceState.name != "High" },
        )
    }
}
