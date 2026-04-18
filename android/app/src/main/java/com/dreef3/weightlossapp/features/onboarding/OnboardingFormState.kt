package com.dreef3.weightlossapp.features.onboarding

import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex

data class OnboardingFormState(
    val firstName: String = "",
    val ageYears: String = "",
    val heightCm: String = "",
    val weightKg: String = "",
    val sex: Sex = Sex.Female,
    val activityLevel: ActivityLevel = ActivityLevel.Moderate,
    val healthConnectCaloriesEnabled: Boolean = false,
)
