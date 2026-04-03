package com.dreef3.weightlossapp.domain.model

import java.time.Instant
import java.time.LocalDate

data class DailyCalorieBudgetPeriod(
    val id: Long = 0,
    val profileId: Long,
    val caloriesPerDay: Int,
    val formulaName: String,
    val activityMultiplier: Double,
    val effectiveFromDate: LocalDate,
    val createdAt: Instant,
)
