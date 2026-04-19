package com.dreef3.weightlossapp.domain.repository

import com.dreef3.weightlossapp.domain.model.FoodEntry
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

interface FoodEntryRepository {
    fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>>
    fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>>
    fun observeAllEntries(): Flow<List<FoodEntry>>
    fun observeEntry(entryId: Long): Flow<FoodEntry?>
    suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry>
    suspend fun getEntry(entryId: Long): FoodEntry?
    suspend fun getPendingModelImprovementUploads(): List<FoodEntry>
    suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant)
    suspend fun upsert(entry: FoodEntry): Long
    suspend fun delete(entry: FoodEntry)
}
