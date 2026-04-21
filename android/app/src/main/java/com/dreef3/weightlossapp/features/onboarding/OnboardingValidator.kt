package com.dreef3.weightlossapp.features.onboarding

object OnboardingValidator {
    data class FieldErrors(
        val firstName: String? = null,
        val ageYears: String? = null,
        val heightCm: String? = null,
        val weightKg: String? = null,
    ) {
        fun hasAny(): Boolean =
            firstName != null || ageYears != null || heightCm != null || weightKg != null

        fun asList(): List<String> = listOfNotNull(firstName, ageYears, heightCm, weightKg)
    }

    fun validateFields(state: OnboardingFormState): FieldErrors = FieldErrors(
        firstName = if (state.firstName.isBlank()) "First name is required" else null,
        ageYears = if (state.ageYears.toIntOrNull() !in 1..120) "Age must be valid" else null,
        heightCm = if (state.heightCm.toIntOrNull() !in 50..300) "Height must be valid" else null,
        weightKg = if (state.weightKg.toIntOrNull() !in 20..500) "Weight must be valid" else null,
    )

    fun validate(state: OnboardingFormState): List<String> {
        return validateFields(state).asList()
    }
}
