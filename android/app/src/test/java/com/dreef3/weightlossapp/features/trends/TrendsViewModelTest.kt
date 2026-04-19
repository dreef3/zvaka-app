package com.dreef3.weightlossapp.features.trends

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.domain.calculation.TrendAggregator
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.PhotoProcessingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun switchesWindowAndMarksPartialHistory() = runTest(dispatcher) {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val periods = listOf(
            DailyCalorieBudgetPeriod(
                profileId = 1,
                caloriesPerDay = 2000,
                formulaName = "mifflin-st-jeor",
                activityMultiplier = 1.2,
                effectiveFromDate = today.minusDays(10),
                createdAt = Instant.EPOCH,
            ),
        )
        val entries = listOf(
            entry(today.minusDays(1), 1200),
            entry(today, 1500),
        )
        val viewModel = TrendsViewModel(
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            profileRepository = TrendsFakeProfileRepository(periods),
            foodEntryRepository = TrendsFakeFoodEntryRepository(entries),
            coachChatRepository = TrendsFakeCoachChatRepository(),
            trendAggregator = TrendAggregator(),
            backgroundPhotoCaptureUseCase = BackgroundPhotoCaptureUseCase(
                repository = TrendsFakeFoodEntryRepository(entries),
                scheduler = NoopTrendPhotoScheduler(),
                localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            ),
        )

        advanceUntilIdle()
        viewModel.selectWindow(TrendWindowType.Last30Days)
        advanceUntilIdle()

        val state = viewModel.uiState
            .dropWhile { it.window == null || it.selectedWindow != TrendWindowType.Last30Days }
            .first()
        assertEquals(TrendWindowType.Last30Days, state.selectedWindow)
        assertTrue(state.window?.isPartial == true)
        assertEquals(11, state.window?.daysIncluded)
    }

    private fun entry(date: LocalDate, calories: Int) = FoodEntry(
        id = date.toEpochDay(),
        capturedAt = Instant.EPOCH,
        entryDate = date,
        imagePath = "/tmp/$date.jpg",
        estimatedCalories = calories,
        finalCalories = calories,
        confidenceState = ConfidenceState.High,
        detectedFoodLabel = "meal",
        confidenceNotes = null,
        confirmationStatus = ConfirmationStatus.Accepted,
        source = FoodEntrySource.AiEstimate,
        entryStatus = FoodEntryStatus.Ready,
    )
}

private class TrendsFakeCoachChatRepository : CoachChatRepository {
    override fun observeSessionForDate(date: LocalDate): Flow<CoachChatSession?> = MutableStateFlow(null)
    override fun observeMessages(sessionId: Long): Flow<List<DietChatMessage>> = MutableStateFlow(emptyList())
    override fun observeSessionsInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CoachChatSession>> =
        MutableStateFlow(emptyList())
    override suspend fun getSession(sessionId: Long): CoachChatSession? = null
    override suspend fun getMessages(sessionId: Long): List<DietChatMessage> = emptyList()
    override suspend fun ensureSessionForDate(date: LocalDate): Long = 1L
    override suspend fun appendMessage(
        sessionId: Long,
        role: ChatRole,
        text: String,
        createdAtEpochMs: Long,
        imagePath: String?,
    ): Long = 1L
    override suspend fun updateSessionSummary(sessionId: Long, summary: String) = Unit
}

private class TrendsFakeFoodEntryRepository(
    entries: List<FoodEntry>,
) : FoodEntryRepository {
    private val flow = MutableStateFlow(entries)

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = flow

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = flow

    override fun observeAllEntries(): Flow<List<FoodEntry>> = flow

    override fun observeEntry(entryId: Long): Flow<FoodEntry?> =
        MutableStateFlow(flow.value.firstOrNull { it.id == entryId })

    override suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry> = flow.value

    override suspend fun getEntry(entryId: Long): FoodEntry? = flow.value.firstOrNull { it.id == entryId }

    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> = emptyList()

    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) = Unit

    override suspend fun upsert(entry: FoodEntry): Long = entry.id

    override suspend fun delete(entry: FoodEntry) = Unit
}

private class TrendsFakeProfileRepository(
    periods: List<DailyCalorieBudgetPeriod>,
) : ProfileRepository {
    private val flow = MutableStateFlow(periods)

    override fun observeProfile(): Flow<UserProfile?> = MutableStateFlow(
        UserProfile(
            id = 1,
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

    override suspend fun getProfile(): UserProfile? = observeProfile().first()

    override suspend fun upsertProfile(profile: UserProfile) = Unit

    override suspend fun addBudgetPeriod(period: DailyCalorieBudgetPeriod) = Unit

    override fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>> = flow

    override suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod? =
        flow.value.filter { it.effectiveFromDate <= date }.maxByOrNull { it.effectiveFromDate }
}

private class NoopTrendPhotoScheduler : PhotoProcessingScheduler {
    override fun enqueue(entryId: Long, imagePath: String, capturedAtEpochMs: Long) = Unit
}
