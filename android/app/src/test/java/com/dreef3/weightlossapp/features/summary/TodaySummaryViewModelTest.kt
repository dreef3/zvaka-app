package com.dreef3.weightlossapp.features.summary

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.domain.calculation.SummaryAggregator
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.PhotoProcessingScheduler
import com.dreef3.weightlossapp.domain.usecase.SaveManualCaloriesUseCase
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
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TodaySummaryViewModelTest {
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
    fun publishesLiveSummaryWhenEntriesChange() = runTest(dispatcher) {
        val date = LocalDate.now(ZoneId.of("UTC"))
        val profileRepository = SummaryFakeProfileRepository(
            budgetPeriods = listOf(
                DailyCalorieBudgetPeriod(
                    profileId = 1,
                    caloriesPerDay = 2000,
                    formulaName = "mifflin-st-jeor",
                    activityMultiplier = 1.55,
                    effectiveFromDate = date,
                    createdAt = Instant.EPOCH,
                ),
            ),
        )
        val foodRepository = SummaryFakeFoodEntryRepository()
        val viewModel = TodaySummaryViewModel(
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            profileRepository = profileRepository,
            foodEntryRepository = foodRepository,
            coachChatRepository = EmptyCoachChatRepository(),
            summaryAggregator = SummaryAggregator(),
            backgroundPhotoCaptureUseCase = BackgroundPhotoCaptureUseCase(
                repository = foodRepository,
                scheduler = NoopPhotoScheduler(),
                localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            ),
            saveManualCaloriesUseCase = SaveManualCaloriesUseCase(foodRepository),
        )

        foodRepository.entries.value = listOf(entry(date, 500), entry(date, 300))
        advanceUntilIdle()

        val state = viewModel.uiState
            .dropWhile { it.summary == null }
            .first()
        assertEquals(800, state.summary?.consumedCalories)
        assertEquals(1200, state.summary?.remainingCalories)
    }

    private fun entry(date: LocalDate, calories: Int) = FoodEntry(
        id = date.toEpochDay(),
        capturedAt = Instant.EPOCH,
        entryDate = date,
        imagePath = "/tmp/meal.jpg",
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

private class NoopPhotoScheduler : PhotoProcessingScheduler {
    override fun enqueue(entryId: Long, imagePath: String, capturedAtEpochMs: Long) = Unit
}

private class SummaryFakeFoodEntryRepository : FoodEntryRepository {
    val entries = MutableStateFlow<List<FoodEntry>>(emptyList())

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = entries

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = entries

    override fun observeAllEntries(): Flow<List<FoodEntry>> = entries

    override suspend fun getEntry(entryId: Long): FoodEntry? = entries.value.firstOrNull { it.id == entryId }

    override suspend fun upsert(entry: FoodEntry): Long = entry.id
}

private class SummaryFakeProfileRepository(
    budgetPeriods: List<DailyCalorieBudgetPeriod>,
) : ProfileRepository {
    private val periods = MutableStateFlow(budgetPeriods)

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

    override fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>> = periods

    override suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod? =
        periods.value.filter { it.effectiveFromDate <= date }.maxByOrNull { it.effectiveFromDate }
}

private class EmptyCoachChatRepository : CoachChatRepository {
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
