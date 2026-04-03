package com.dreef3.weightlossapp.domain.calculation

import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Test

class CalorieBudgetCalculatorTest {
    private val calculator = CalorieBudgetCalculator()

    @Test
    fun calculatesMaleBudgetWithActivityMultiplier() {
        val calories = calculator.calculateCaloriesPerDay(
            sex = Sex.Male,
            ageYears = 30,
            weightKg = 80.0,
            heightCm = 180,
            activityLevel = ActivityLevel.Moderate,
        )

        assertEquals(2759, calories)
    }

    @Test
    fun floorsVeryLowBudgetsToMinimumGuardrail() {
        val calories = calculator.calculateCaloriesPerDay(
            sex = Sex.Female,
            ageYears = 60,
            weightKg = 35.0,
            heightCm = 145,
            activityLevel = ActivityLevel.Sedentary,
        )

        assertEquals(1200, calories)
    }
}
