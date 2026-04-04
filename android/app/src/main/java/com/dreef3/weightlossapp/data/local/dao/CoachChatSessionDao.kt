package com.dreef3.weightlossapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dreef3.weightlossapp.data.local.entity.CoachChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachChatSessionDao {
    @Query("SELECT * FROM coach_chat_session WHERE sessionDateIso = :dateIso LIMIT 1")
    fun observeForDate(dateIso: String): Flow<CoachChatSessionEntity?>

    @Query("SELECT * FROM coach_chat_session WHERE sessionDateIso = :dateIso LIMIT 1")
    suspend fun getForDate(dateIso: String): CoachChatSessionEntity?

    @Query("SELECT * FROM coach_chat_session WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: Long): CoachChatSessionEntity?

    @Query("SELECT * FROM coach_chat_session WHERE sessionDateIso BETWEEN :startIso AND :endIso ORDER BY sessionDateIso DESC, updatedAtEpochMs DESC")
    fun observeInRange(startIso: String, endIso: String): Flow<List<CoachChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: CoachChatSessionEntity): Long

    @Update
    suspend fun update(session: CoachChatSessionEntity)
}
