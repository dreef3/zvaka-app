package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.TrendWindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrendAggregatorTest {
    private val aggregator = TrendAggregator()

    @Test
    fun preservesHistoricalBudgetsAcrossWindow() {
        val endDate = LocalDate.parse("2026-04-03")
        val trend = aggregator.buildTrendWindow(
            type = TrendWindowType.Last7Days,
            endDate = endDate,
            dailyBudgets = mapOf(
                endDate.minusDays(2) to 2000,
                endDate.minusDays(1) to 1800,
                endDate to 1800,
            ),
            consumedByDate = mapOf(
                endDate.minusDays(2) to 1500,
                endDate.minusDays(1) to 1750,
                endDate to 1600,
            ),
        )

        assertEquals(5600, trend.totalBudgetCalories)
        assertEquals(4850, trend.totalConsumedCalories)
        assertTrue(trend.isPartial)
    }
}
