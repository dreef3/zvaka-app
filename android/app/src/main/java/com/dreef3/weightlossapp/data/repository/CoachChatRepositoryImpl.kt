package com.dreef3.weightlossapp.data.repository

import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.data.local.dao.CoachChatMessageDao
import com.dreef3.weightlossapp.data.local.dao.CoachChatSessionDao
import com.dreef3.weightlossapp.data.local.entity.CoachChatMessageEntity
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class CoachChatRepositoryImpl(
    private val sessionDao: CoachChatSessionDao,
    private val messageDao: CoachChatMessageDao,
) : CoachChatRepository {
    override fun observeSessionForDate(date: LocalDate): Flow<CoachChatSession?> =
        sessionDao.observeForDate(date.toString()).map { it?.toDomain() }

    override fun observeMessages(sessionId: Long): Flow<List<DietChatMessage>> =
        messageDao.observeForSession(sessionId).map { items -> items.map { it.toDomain() } }

    override fun observeSessionsInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CoachChatSession>> =
        sessionDao.observeInRange(startDate.toString(), endDate.toString())
            .map { items -> items.map { it.toDomain() } }

    override suspend fun getSession(sessionId: Long): CoachChatSession? = sessionDao.getById(sessionId)?.toDomain()

    override suspend fun getMessages(sessionId: Long): List<DietChatMessage> =
        messageDao.getForSession(sessionId).map { it.toDomain() }

    override suspend fun ensureSessionForDate(date: LocalDate): Long {
        val existing = sessionDao.getForDate(date.toString())
        if (existing != null) return existing.id
        val now = System.currentTimeMillis()
        return sessionDao.insert(
            CoachChatSession(
                sessionDateIso = date.toString(),
                summary = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ).toEntity(),
        )
    }

    override suspend fun appendMessage(
        sessionId: Long,
        role: ChatRole,
        text: String,
        createdAtEpochMs: Long,
        imagePath: String?,
    ): Long {
        val id = messageDao.insert(
            CoachChatMessageEntity(
                sessionId = sessionId,
                role = role.name,
                text = text,
                createdAtEpochMs = createdAtEpochMs,
                imagePath = imagePath,
            ),
        )
        sessionDao.getById(sessionId)?.let { existing ->
            sessionDao.update(existing.copy(updatedAtEpochMs = createdAtEpochMs))
        }
        return id
    }

    override suspend fun updateSessionSummary(sessionId: Long, summary: String) {
        sessionDao.getById(sessionId)?.let { existing ->
            sessionDao.update(
                existing.copy(
                    summary = summary,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
