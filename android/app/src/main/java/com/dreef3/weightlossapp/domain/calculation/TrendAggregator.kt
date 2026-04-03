package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import java.time.LocalDate

class TrendAggregator {
    fun buildTrendWindow(
        type: TrendWindowType,
        endDate: LocalDate,
        dailyBudgets: Map<LocalDate, Int>,
        consumedByDate: Map<LocalDate, Int>,
    ): TrendWindow {
        val days = when (type) {
            TrendWindowType.Last7Days -> 7L
            TrendWindowType.Last30Days -> 30L
        }
        val start = endDate.minusDays(days - 1)
        val dates = generateSequence(start) { current ->
            if (current >= endDate) null else current.plusDays(1)
        }.toList()
        val availableDates = dates.filter { dailyBudgets.containsKey(it) || consumedByDate.containsKey(it) }
        val totalBudget = availableDates.sumOf { dailyBudgets[it] ?: 0 }
        val totalConsumed = availableDates.sumOf { consumedByDate[it] ?: 0 }
        val count = availableDates.size.coerceAtLeast(1)
        return TrendWindow(
            windowType = type,
            startDate = start,
            endDate = endDate,
            daysIncluded = availableDates.size,
            totalConsumedCalories = totalConsumed,
            totalBudgetCalories = totalBudget,
            averageConsumedCalories = totalConsumed.toDouble() / count,
            averageRemainingCalories = (totalBudget - totalConsumed).toDouble() / count,
            isPartial = availableDates.size < days,
        )
    }
}
