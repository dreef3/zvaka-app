package com.dreef3.weightlossapp.domain.model

import java.time.LocalDate

enum class TrendWindowType {
    Last7Days,
    Last30Days,
}

data class DailySummary(
    val date: LocalDate,
    val budgetCalories: Int,
    val consumedCalories: Int,
    val remainingCalories: Int,
    val status: String,
    val entryCount: Int,
    val hasLimitedConfidenceEntries: Boolean,
)

data class TrendWindow(
    val windowType: TrendWindowType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val daysIncluded: Int,
    val totalConsumedCalories: Int,
    val totalBudgetCalories: Int,
    val averageConsumedCalories: Double,
    val averageRemainingCalories: Double,
    val isPartial: Boolean,
)
