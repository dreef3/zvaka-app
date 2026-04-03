package com.dreef3.weightlossapp.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FoodEntryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: FoodEntryRepositoryImpl
    private val date = LocalDate.parse("2026-04-03")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = FoodEntryRepositoryImpl(db.foodEntryDao())
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }
    }

    @Test
    fun createsEditsAndSoftDeletesEntries() = runTest {
        val createdId = repository.upsert(entry())
        val created = repository.observeEntriesFor(date).first().single()
        assertEquals(createdId, created.id)
        assertEquals(450, created.finalCalories)

        repository.upsert(created.copy(finalCalories = 520, source = FoodEntrySource.UserCorrected))
        val edited = repository.observeEntriesFor(date).first().single()
        assertEquals(520, edited.finalCalories)
        assertEquals(FoodEntrySource.UserCorrected, edited.source)

        val deletedAt = Instant.parse("2026-04-03T13:00:00Z")
        repository.upsert(edited.copy(deletedAt = deletedAt))
        val deleted = repository.observeEntriesFor(date).first().single()
        assertEquals(deletedAt, deleted.deletedAt)
    }

    @Test
    fun persistsRejectedEntriesWithoutTreatingThemAsAcceptedSaves() = runTest {
        repository.upsert(
            entry(
                confirmationStatus = ConfirmationStatus.Rejected,
                detectedFoodLabel = "wrong food",
            ),
        )

        val stored = repository.observeEntriesFor(date).first().single()
        assertNotNull(stored)
        assertEquals(ConfirmationStatus.Rejected, stored.confirmationStatus)
        assertEquals("wrong food", stored.detectedFoodLabel)
    }

    private fun entry(
        confirmationStatus: ConfirmationStatus = ConfirmationStatus.NotRequired,
        detectedFoodLabel: String? = "pasta",
    ) = FoodEntry(
        capturedAt = Instant.parse("2026-04-03T12:00:00Z"),
        entryDate = date,
        imagePath = "/tmp/meal.jpg",
        estimatedCalories = 450,
        finalCalories = 450,
        confidenceState = ConfidenceState.High,
        detectedFoodLabel = detectedFoodLabel,
        confidenceNotes = null,
        confirmationStatus = confirmationStatus,
        source = FoodEntrySource.AiEstimate,
        entryStatus = FoodEntryStatus.Ready,
    )
}
