package com.dreef3.weightlossapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dreef3.weightlossapp.data.local.entity.FoodEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FoodEntryEntity): Long

    @Query("SELECT * FROM food_entry WHERE entryDateIso = :dateIso")
    fun observeForDate(dateIso: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry WHERE entryDateIso BETWEEN :startIso AND :endIso")
    fun observeInRange(startIso: String, endIso: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry ORDER BY capturedAtEpochMs DESC")
    fun observeAll(): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry WHERE id = :entryId LIMIT 1")
    suspend fun getById(entryId: Long): FoodEntryEntity?

    @Update
    suspend fun update(entry: FoodEntryEntity)
}
