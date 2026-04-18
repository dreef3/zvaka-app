package com.dreef3.weightlossapp.features.onboarding

import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.app.network.NetworkConnectionMonitor
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors
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
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

        viewModel.requestModelDownload()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, modelController.enqueueCalls)

        storage.fileFor(ModelDescriptors.gemma).writeText("model")
        modelController.state.value = ModelDownloadState(isDownloading = false, progressPercent = 100)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertEquals(
            OnboardingStep.Ready,
            viewModel.uiState.filter { it.step == OnboardingStep.Ready }.first().step,
        )
    }

    @Test
    fun requestModelDownloadOnCellularShowsConfirmationBeforeStarting() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val modelController = FakeModelDownloadController()
        val viewModel = createViewModel(
            repository = repository,
            storage = storage,
            modelController = modelController,
            networkConnectionMonitor = FakeNetworkConnectionMonitor(NetworkConnectionType.Cellular),
        )

        viewModel.requestModelDownload()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCellularDownloadConfirmation)
        assertEquals(0, modelController.enqueueCalls)

        viewModel.confirmCellularModelDownload()
        advanceUntilIdle()

        assertEquals(OnboardingStep.Downloading, viewModel.uiState.value.step)
        assertEquals(1, modelController.enqueueCalls)
    }

    @Test
    fun completeSetupMarksOnboardingCompleted() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val preferences = testPreferences()
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

    @Test
    fun completeSetupPersistsHealthConnectOptIn() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val storage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val preferences = testPreferences()
        val viewModel = createViewModel(
            repository = repository,
            storage = storage,
            modelController = FakeModelDownloadController(),
            preferences = preferences,
        )

        viewModel.updateForm { it.copy(healthConnectCaloriesEnabled = true) }
        viewModel.completeSetup()
        advanceUntilIdle()

        assertTrue(preferences.healthConnectCaloriesEnabled.dropWhile { !it }.first())
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
    }

    private fun createViewModel(
        repository: FakeProfileRepository,
        storage: ModelStorage,
        modelController: FakeModelDownloadController,
        preferences: AppPreferences = testPreferences(),
        networkConnectionMonitor: NetworkConnectionMonitor = FakeNetworkConnectionMonitor(NetworkConnectionType.Wifi),
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
            networkConnectionMonitor = networkConnectionMonitor,
        )
    }

    private fun testPreferences(): AppPreferences = AppPreferences(
        context = ApplicationProvider.getApplicationContext(),
        dataStoreName = "test-onboarding-prefs-${System.nanoTime()}",
    )
}

private class FakeNetworkConnectionMonitor(
    private val type: NetworkConnectionType,
) : NetworkConnectionMonitor(ApplicationProvider.getApplicationContext()) {
    override fun currentConnectionType(): NetworkConnectionType = type
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

    override fun enqueueIfNeeded(model: ModelDescriptor) {
        enqueueCalls += 1
        state.value = ModelDownloadState(isDownloading = true, progressPercent = 0)
    }

    override fun observeState(model: ModelDescriptor): Flow<ModelDownloadState> = state
}
