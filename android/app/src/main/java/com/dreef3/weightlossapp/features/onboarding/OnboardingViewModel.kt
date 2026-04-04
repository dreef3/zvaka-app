package com.dreef3.weightlossapp.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.domain.calculation.CalorieBudgetCalculator
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    DownloadIntro,
    Profile,
    BudgetPreview,
    Downloading,
    Ready,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.DownloadIntro,
    val form: OnboardingFormState = OnboardingFormState(),
    val errors: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isCompleted: Boolean = false,
    val estimatedBudgetCalories: Int? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState(),
)

class OnboardingViewModel(
    private val profileRepository: ProfileRepository,
    private val saveUserProfile: suspend (SaveUserProfileRequest) -> Unit,
    private val preferences: AppPreferences,
    private val budgetCalculator: CalorieBudgetCalculator,
    private val modelDownloadController: ModelDownloadController,
    private val modelStorage: ModelStorage,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                profileRepository.observeProfile(),
                modelDownloadController.observeState(),
            ) { profile, downloadState -> profile to downloadState }
                .collect { (profile, downloadState) ->
                    _uiState.update { current ->
                        val populatedForm = if (profile != null && current.form.firstName.isBlank()) {
                            current.form.copy(
                                firstName = profile.firstName,
                                ageYears = profile.ageYears.toString(),
                                heightCm = profile.heightCm.toString(),
                                weightKg = profile.weightKg.toInt().toString(),
                                sex = profile.sex,
                                activityLevel = profile.activityLevel,
                            )
                        } else {
                            current.form
                        }

                        val estimatedBudget = populatedForm.estimatedBudgetOrNull(budgetCalculator)
                        val nextStep = when {
                            current.step == OnboardingStep.Downloading && modelStorage.hasUsableModel() ->
                                OnboardingStep.Ready
                            current.step == OnboardingStep.BudgetPreview &&
                                modelStorage.hasUsableModel() ->
                                OnboardingStep.Ready
                            else -> current.step
                        }

                        current.copy(
                            step = nextStep,
                            form = populatedForm,
                            estimatedBudgetCalories = estimatedBudget,
                            modelDownloadState = downloadState,
                        )
                    }
                }
        }
    }

    fun updateForm(transform: (OnboardingFormState) -> OnboardingFormState) {
        _uiState.update { current ->
            val updatedForm = transform(current.form)
            current.copy(
                form = updatedForm,
                estimatedBudgetCalories = updatedForm.estimatedBudgetOrNull(budgetCalculator),
            )
        }
    }

    fun continueFromIntro() {
        _uiState.update { it.copy(step = OnboardingStep.Profile) }
    }

    fun backFromProfile() {
        _uiState.update { it.copy(step = OnboardingStep.DownloadIntro, errors = emptyList()) }
    }

    fun submitProfile() {
        val issues = OnboardingValidator.validate(_uiState.value.form)
        if (issues.isNotEmpty()) {
            _uiState.update { it.copy(errors = issues) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errors = emptyList()) }
            val form = _uiState.value.form
            saveUserProfile(
                SaveUserProfileRequest(
                    firstName = form.firstName,
                    sex = form.sex,
                    ageYears = form.ageYears.toInt(),
                    heightCm = form.heightCm.toInt(),
                    weightKg = form.weightKg.toInt().toDouble(),
                    activityLevel = form.activityLevel,
                ),
            )
            _uiState.update {
                it.copy(
                    isSaving = false,
                    step = OnboardingStep.BudgetPreview,
                    estimatedBudgetCalories = form.estimatedBudgetOrNull(budgetCalculator),
                )
            }
        }
    }

    fun startModelDownload() {
        modelDownloadController.enqueueIfNeeded()
        _uiState.update {
            it.copy(
                step = if (modelStorage.hasUsableModel()) OnboardingStep.Ready else OnboardingStep.Downloading,
            )
        }
    }

    fun completeSetup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleted = true) }
            preferences.setCompletedOnboarding(true)
        }
    }
}

private fun OnboardingFormState.estimatedBudgetOrNull(calorieBudgetCalculator: CalorieBudgetCalculator): Int? {
    val age = ageYears.toIntOrNull() ?: return null
    val height = heightCm.toIntOrNull() ?: return null
    val weight = weightKg.toIntOrNull() ?: return null
    return calorieBudgetCalculator.calculateCaloriesPerDay(
        sex = sex,
        ageYears = age,
        weightKg = weight.toDouble(),
        heightCm = height,
        activityLevel = activityLevel,
    )
}
