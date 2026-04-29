package com.dreef3.weightlossapp.app

import android.os.Looper
import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.Sex
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppStateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun setupIsNotCompleteWhenOnboardingWasRestoredButGemmaModelIsMissing() = runTest(dispatcher) {
        val repository = FakeProfileRepository()
        val preferences = AppPreferences(
            context = RuntimeEnvironment.getApplication(),
            dataStoreName = "test-app-state-prefs-${System.nanoTime()}",
        )
        val modelStorage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile())
        val modelDownloadController = FakeModelDownloadController()
        repository.profile.value = sampleProfile()
        preferences.setCompletedOnboarding(true)

        val viewModel = AppStateViewModel(repository, preferences, modelStorage, modelDownloadController)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isSetupComplete)
    }

    private fun sampleProfile() = UserProfile(
        id = 1L,
        firstName = "Ana",
        sex = Sex.Female,
        ageYears = 30,
        heightCm = 170,
        weightKg = 70.0,
        activityLevel = ActivityLevel.Moderate,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
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
    override fun enqueueIfNeeded(model: ModelDescriptor, allowCellular: Boolean) = Unit

    override fun observeState(model: ModelDescriptor): Flow<ModelDownloadState> = flowOf(ModelDownloadState())
}
