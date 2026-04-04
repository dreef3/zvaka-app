package com.dreef3.weightlossapp.domain.repository

import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.chat.ChatRole
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface CoachChatRepository {
    fun observeSessionForDate(date: LocalDate): Flow<CoachChatSession?>
    fun observeMessages(sessionId: Long): Flow<List<DietChatMessage>>
    fun observeSessionsInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CoachChatSession>>
    suspend fun getSession(sessionId: Long): CoachChatSession?
    suspend fun getMessages(sessionId: Long): List<DietChatMessage>
    suspend fun ensureSessionForDate(date: LocalDate): Long
    suspend fun appendMessage(
        sessionId: Long,
        role: ChatRole,
        text: String,
        createdAtEpochMs: Long,
        imagePath: String? = null,
    ): Long
    suspend fun updateSessionSummary(sessionId: Long, summary: String)
}
