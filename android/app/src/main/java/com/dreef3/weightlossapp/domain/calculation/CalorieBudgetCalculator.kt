package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex
import kotlin.math.roundToInt

class CalorieBudgetCalculator {
    fun calculateCaloriesPerDay(
        sex: Sex,
        ageYears: Int,
        weightKg: Double,
        heightCm: Int,
        activityLevel: ActivityLevel,
    ): Int {
        val resting = when (sex) {
            Sex.Male -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5
            Sex.Female -> 10 * weightKg + 6.25 * heightCm - 5 * ageYears - 161
        }
        return (resting * activityLevel.multiplier).roundToInt().coerceAtLeast(1_200)
    }
}
