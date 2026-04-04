package com.dreef3.weightlossapp.features.onboarding

import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.domain.calculation.CalorieBudgetCalculator
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
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
    fun submitProfileMovesToBudgetPreviewWithCalculatedCalories() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val viewModel = createViewModel(repository, storage, FakeModelDownloadController())

        viewModel.continueFromIntro()
        viewModel.updateForm {
            it.copy(
                firstName = "Ana",
                ageYears = "30",
                heightCm = "170",
                weightKg = "70",
                sex = Sex.Female,
                activityLevel = ActivityLevel.Moderate,
            )
        }
        viewModel.submitProfile()
        advanceUntilIdle()

        assertEquals(OnboardingStep.BudgetPreview, viewModel.uiState.value.step)
        assertEquals(2250, viewModel.uiState.value.estimatedBudgetCalories)
        assertEquals("Ana", repository.profile.value?.firstName)
    }

    @Test
    fun startModelDownloadMovesToDownloadingAndThenReady() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val modelController = FakeModelDownloadController()
        val viewModel = createViewModel(repository, storage, modelController)

        viewModel.startModelDownload()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Downloading, viewModel.uiState.value.step)

        storage.defaultModelFile.writeText("model")
        modelController.state.value = ModelDownloadState(isDownloading = false, progressPercent = 100)
        advanceUntilIdle()

        assertEquals(OnboardingStep.Ready, viewModel.uiState.value.step)
    }

    @Test
    fun completeSetupMarksOnboardingCompleted() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val preferences = AppPreferences(ApplicationProvider.getApplicationContext())
        preferences.setCompletedOnboarding(false)
        val viewModel = createViewModel(
            repository = repository,
            storage = storage,
            modelController = FakeModelDownloadController(),
            preferences = preferences,
        )

        viewModel.completeSetup()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCompleted)
    }

    private fun createViewModel(
        repository: FakeProfileRepository,
        storage: ModelStorage,
        modelController: FakeModelDownloadController,
        preferences: AppPreferences = AppPreferences(ApplicationProvider.getApplicationContext()),
    ): OnboardingViewModel {
        val saveUseCase = SaveUserProfileUseCase(
            profileRepository = repository,
            calorieBudgetCalculator = CalorieBudgetCalculator(),
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
        )
        return OnboardingViewModel(
            profileRepository = repository,
            saveUserProfile = saveUseCase::invoke,
            preferences = preferences,
            budgetCalculator = CalorieBudgetCalculator(),
            modelDownloadController = modelController,
            modelStorage = storage,
        )
    }
}

private class FakeProfileRepository : ProfileRepository {
    val profile = MutableStateFlow<UserProfile?>(null)
    val budgetPeriods = MutableStateFlow<List<DailyCalorieBudgetPeriod>>(emptyList())

    override fun observeProfile(): Flow<UserProfile?> = profile

    override suspend fun getProfile(): UserProfile? = profile.value

    override suspend fun upsertProfile(profile: UserProfile) {
        this.profile.value = profile
    }

    override suspend fun addBudgetPeriod(period: DailyCalorieBudgetPeriod) {
        budgetPeriods.value = budgetPeriods.value + period
    }

    override fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>> = budgetPeriods

    override suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod? =
        budgetPeriods.value.filter { it.effectiveFromDate <= date }.maxByOrNull { it.effectiveFromDate }
}

private class FakeModelDownloadController : ModelDownloadController {
    val state = MutableStateFlow(ModelDownloadState())
    var enqueueCalls = 0

    override fun enqueueIfNeeded() {
        enqueueCalls += 1
    }

    override fun observeState(): Flow<ModelDownloadState> = state
}
