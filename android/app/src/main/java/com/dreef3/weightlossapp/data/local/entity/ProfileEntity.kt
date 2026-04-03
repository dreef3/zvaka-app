package com.dreef3.weightlossapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Long = 1,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val firstName: String,
    val sex: String,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
    val activityLevel: String,
)
