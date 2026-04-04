package com.dreef3.weightlossapp.data.repository

import com.dreef3.weightlossapp.data.local.dao.FoodEntryDao
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class FoodEntryRepositoryImpl(
    private val foodEntryDao: FoodEntryDao,
) : FoodEntryRepository {
    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> =
        foodEntryDao.observeForDate(date.toString()).map { items -> items.map { it.toDomain() } }

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> =
        foodEntryDao.observeInRange(startDate.toString(), endDate.toString())
            .map { items -> items.map { it.toDomain() } }

    override fun observeAllEntries(): Flow<List<FoodEntry>> =
        foodEntryDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun getEntry(entryId: Long): FoodEntry? = foodEntryDao.getById(entryId)?.toDomain()

    override suspend fun upsert(entry: FoodEntry): Long = foodEntryDao.upsert(entry.toEntity())

    override suspend fun delete(entry: FoodEntry) {
        foodEntryDao.update(
            entry.copy(
                deletedAt = java.time.Instant.now(),
            ).toEntity(),
        )
    }
}
