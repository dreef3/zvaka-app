package com.dreef3.weightlossapp.domain.model

import java.time.Instant

data class UserProfile(
    val id: Long = 1,
    val createdAt: Instant,
    val updatedAt: Instant,
    val firstName: String,
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
    val activityLevel: ActivityLevel,
)
