package com.dreef3.weightlossapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dreef3.weightlossapp.data.local.entity.CoachChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachChatMessageDao {
    @Query("SELECT * FROM coach_chat_message WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<CoachChatMessageEntity>>

    @Query("SELECT * FROM coach_chat_message WHERE sessionId = :sessionId ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<CoachChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: CoachChatMessageEntity): Long
}
