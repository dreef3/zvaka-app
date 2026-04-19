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
    suspend fun insert(entry: FoodEntryEntity): Long

    @Query("SELECT * FROM food_entry WHERE entryDateIso = :dateIso")
    fun observeForDate(dateIso: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry WHERE entryDateIso BETWEEN :startIso AND :endIso")
    fun observeInRange(startIso: String, endIso: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry WHERE entryDateIso BETWEEN :startIso AND :endIso ORDER BY capturedAtEpochMs DESC")
    suspend fun getInRange(startIso: String, endIso: String): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entry ORDER BY capturedAtEpochMs DESC")
    fun observeAll(): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entry ORDER BY capturedAtEpochMs DESC")
    suspend fun getAll(): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entry WHERE id = :entryId LIMIT 1")
    suspend fun getById(entryId: Long): FoodEntryEntity?

    @Query("SELECT * FROM food_entry WHERE id = :entryId LIMIT 1")
    fun observeById(entryId: Long): Flow<FoodEntryEntity?>

    @Query(
        """
        SELECT * FROM food_entry
        WHERE deletedAtEpochMs IS NULL
          AND modelImprovementUploadedAtEpochMs IS NULL
        ORDER BY capturedAtEpochMs ASC
        """,
    )
    suspend fun getPendingModelImprovementUploads(): List<FoodEntryEntity>

    @Query(
        """
        UPDATE food_entry
        SET modelImprovementUploadedAtEpochMs = :uploadedAtEpochMs
        WHERE id = :entryId
        """,
    )
    suspend fun markModelImprovementUploaded(entryId: Long, uploadedAtEpochMs: Long)

    @Update
    suspend fun update(entry: FoodEntryEntity): Int
}
