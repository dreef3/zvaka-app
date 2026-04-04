package com.dreef3.weightlossapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food_entry")
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAtEpochMs: Long,
    val entryDateIso: String,
    val imagePath: String,
    val estimatedCalories: Int,
    val finalCalories: Int,
    val confidenceState: String,
    val detectedFoodLabel: String?,
    val confidenceNotes: String?,
    val confirmationStatus: String,
    val source: String,
    val entryStatus: String,
    val debugInteractionLog: String?,
    val deletedAtEpochMs: Long?,
)
