package com.dreef3.weightlossapp.testutil

import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import java.time.Instant
import java.time.LocalDate

object ProfileFixtures {
    fun userProfile(
        id: Long = 1,
        firstName: String = "Alex",
    ) = UserProfile(
        id = id,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        firstName = firstName,
        sex = Sex.Male,
        ageYears = 34,
        heightCm = 180,
        weightKg = 82.0,
        activityLevel = ActivityLevel.Active,
    )

    fun budgetPeriod(
        profileId: Long = 1,
        caloriesPerDay: Int = 2000,
        effectiveFromDate: LocalDate = LocalDate.parse("2026-04-03"),
    ) = DailyCalorieBudgetPeriod(
        profileId = profileId,
        caloriesPerDay = caloriesPerDay,
        formulaName = "mifflin-st-jeor",
        activityMultiplier = 1.55,
        effectiveFromDate = effectiveFromDate,
        createdAt = Instant.EPOCH,
    )
}
