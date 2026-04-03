package com.dreef3.weightlossapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budget_period")
data class DailyCalorieBudgetPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val caloriesPerDay: Int,
    val formulaName: String,
    val activityMultiplier: Double,
    val effectiveFromDateIso: String,
    val createdAtEpochMs: Long,
)
