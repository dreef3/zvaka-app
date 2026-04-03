package com.dreef3.weightlossapp.domain.usecase

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.calculation.CalorieBudgetCalculator
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import java.time.Instant

data class SaveUserProfileRequest(
    val firstName: String,
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
    val activityLevel: ActivityLevel,
)

class SaveUserProfileUseCase(
    private val profileRepository: ProfileRepository,
    private val calorieBudgetCalculator: CalorieBudgetCalculator,
    private val localDateProvider: LocalDateProvider,
) {
    suspend operator fun invoke(request: SaveUserProfileRequest): UserProfile {
        val now = Instant.now()
        val existing = profileRepository.getProfile()
        val profile = UserProfile(
            id = existing?.id ?: 1,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            firstName = request.firstName.trim(),
            sex = request.sex,
            ageYears = request.ageYears,
            heightCm = request.heightCm,
            weightKg = request.weightKg,
            activityLevel = request.activityLevel,
        )
        val caloriesPerDay = calorieBudgetCalculator.calculateCaloriesPerDay(
            sex = request.sex,
            ageYears = request.ageYears,
            weightKg = request.weightKg,
            heightCm = request.heightCm,
            activityLevel = request.activityLevel,
        )

        profileRepository.upsertProfile(profile)
        profileRepository.addBudgetPeriod(
            DailyCalorieBudgetPeriod(
                profileId = profile.id,
                caloriesPerDay = caloriesPerDay,
                formulaName = "mifflin-st-jeor",
                activityMultiplier = request.activityLevel.multiplier,
                effectiveFromDate = localDateProvider.today(),
                createdAt = now,
            ),
        )
        return profile
    }
}
