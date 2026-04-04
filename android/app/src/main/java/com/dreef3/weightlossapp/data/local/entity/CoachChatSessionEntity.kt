package com.dreef3.weightlossapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coach_chat_session")
data class CoachChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionDateIso: String,
    val summary: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
