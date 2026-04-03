package com.dreef3.weightlossapp.features.onboarding

object OnboardingValidator {
    fun validate(state: OnboardingFormState): List<String> {
        val issues = mutableListOf<String>()
        if (state.firstName.isBlank()) issues += "First name is required"
        if (state.ageYears.toIntOrNull() !in 1..120) issues += "Age must be valid"
        if (state.heightCm.toIntOrNull() !in 50..300) issues += "Height must be valid"
        if (state.weightKg.toIntOrNull() !in 20..500) issues += "Weight must be valid"
        return issues
    }
}
