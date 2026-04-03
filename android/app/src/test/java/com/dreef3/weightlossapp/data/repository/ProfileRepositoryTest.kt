package com.dreef3.weightlossapp.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
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

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ProfileRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ProfileRepositoryImpl(db.profileDao(), db.dailyCalorieBudgetPeriodDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun persistsProfileAndExposesItAsFlow() = runTest {
        repository.upsertProfile(
            UserProfile(
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                firstName = "Alex",
                sex = Sex.Male,
                ageYears = 34,
                heightCm = 180,
                weightKg = 82.0,
                activityLevel = ActivityLevel.Active,
            ),
        )

        val profile = repository.observeProfile().first()
        assertNotNull(profile)
        assertEquals("Alex", profile?.firstName)
    }
}
