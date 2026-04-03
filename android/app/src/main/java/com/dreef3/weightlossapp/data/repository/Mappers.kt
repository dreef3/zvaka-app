package com.dreef3.weightlossapp.data.repository

import com.dreef3.weightlossapp.data.local.entity.DailyCalorieBudgetPeriodEntity
import com.dreef3.weightlossapp.data.local.entity.FoodEntryEntity
import com.dreef3.weightlossapp.data.local.entity.ProfileEntity
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import java.time.Instant
import java.time.LocalDate

internal fun ProfileEntity.toDomain(): UserProfile = UserProfile(
    id = id,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
    firstName = firstName,
    sex = Sex.valueOf(sex),
    ageYears = ageYears,
    heightCm = heightCm,
    weightKg = weightKg,
    activityLevel = ActivityLevel.valueOf(activityLevel),
)

internal fun UserProfile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    createdAtEpochMs = createdAt.toEpochMilli(),
    updatedAtEpochMs = updatedAt.toEpochMilli(),
    firstName = firstName,
    sex = sex.name,
    ageYears = ageYears,
    heightCm = heightCm,
    weightKg = weightKg,
    activityLevel = activityLevel.name,
)

internal fun DailyCalorieBudgetPeriodEntity.toDomain(): DailyCalorieBudgetPeriod = DailyCalorieBudgetPeriod(
    id = id,
    profileId = profileId,
    caloriesPerDay = caloriesPerDay,
    formulaName = formulaName,
    activityMultiplier = activityMultiplier,
    effectiveFromDate = LocalDate.parse(effectiveFromDateIso),
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
)

internal fun DailyCalorieBudgetPeriod.toEntity(): DailyCalorieBudgetPeriodEntity = DailyCalorieBudgetPeriodEntity(
    id = id,
    profileId = profileId,
    caloriesPerDay = caloriesPerDay,
    formulaName = formulaName,
    activityMultiplier = activityMultiplier,
    effectiveFromDateIso = effectiveFromDate.toString(),
    createdAtEpochMs = createdAt.toEpochMilli(),
)

internal fun FoodEntryEntity.toDomain(): FoodEntry = FoodEntry(
    id = id,
    capturedAt = Instant.ofEpochMilli(capturedAtEpochMs),
    entryDate = LocalDate.parse(entryDateIso),
    imagePath = imagePath,
    estimatedCalories = estimatedCalories,
    finalCalories = finalCalories,
    confidenceState = ConfidenceState.valueOf(confidenceState),
    detectedFoodLabel = detectedFoodLabel,
    confidenceNotes = confidenceNotes,
    confirmationStatus = ConfirmationStatus.valueOf(confirmationStatus),
    source = FoodEntrySource.valueOf(source),
    entryStatus = FoodEntryStatus.valueOf(entryStatus),
    deletedAt = deletedAtEpochMs?.let(Instant::ofEpochMilli),
)

internal fun FoodEntry.toEntity(): FoodEntryEntity = FoodEntryEntity(
    id = id,
    capturedAtEpochMs = capturedAt.toEpochMilli(),
    entryDateIso = entryDate.toString(),
    imagePath = imagePath,
    estimatedCalories = estimatedCalories,
    finalCalories = finalCalories,
    confidenceState = confidenceState.name,
    detectedFoodLabel = detectedFoodLabel,
    confidenceNotes = confidenceNotes,
    confirmationStatus = confirmationStatus.name,
    source = source.name,
    entryStatus = entryStatus.name,
    deletedAtEpochMs = deletedAt?.toEpochMilli(),
)
