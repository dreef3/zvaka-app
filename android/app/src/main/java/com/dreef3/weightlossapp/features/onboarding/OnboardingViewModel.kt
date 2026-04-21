package com.dreef3.weightlossapp.features.onboarding

import com.dreef3.weightlossapp.app.network.NetworkConnectionMonitor
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.dreef3.weightlossapp.app.health.HealthConnectBackfillService
import com.dreef3.weightlossapp.app.training.ModelImprovementUploadScheduler
import com.dreef3.weightlossapp.app.training.ModelImprovementUploader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.media.ModelDescriptors
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
import kotlinx.coroutines.flow.first
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
    val fieldErrors: OnboardingValidator.FieldErrors = OnboardingValidator.FieldErrors(),
    val hasAttemptedProfileSubmit: Boolean = false,
    val isSaving: Boolean = false,
    val isCompleted: Boolean = false,
    val estimatedBudgetCalories: Int? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState(),
    val showCellularDownloadConfirmation: Boolean = false,
    val hasInitializedPreferenceChoices: Boolean = false,
)

class OnboardingViewModel(
    private val profileRepository: ProfileRepository,
    private val saveUserProfile: suspend (SaveUserProfileRequest) -> Unit,
    private val preferences: AppPreferences,
    private val budgetCalculator: CalorieBudgetCalculator,
    private val modelDownloadController: ModelDownloadController,
    private val modelStorage: ModelStorage,
    private val networkConnectionMonitor: NetworkConnectionMonitor,
    private val healthConnectBackfillService: HealthConnectBackfillService? = null,
    private val modelImprovementUploadScheduler: ModelImprovementUploadScheduler? = null,
    private val modelImprovementUploader: ModelImprovementUploader? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                profileRepository.observeProfile(),
                preferences.hasCompletedOnboarding,
                preferences.healthConnectCaloriesEnabled,
                preferences.trainingDataSharingEnabled,
                modelDownloadController.observeState(ModelDescriptors.gemma),
            ) { profile, hasCompletedOnboarding, healthConnectEnabled, trainingDataSharingEnabled, downloadState ->
                OnboardingBootstrapState(
                    profile = profile,
                    hasCompletedOnboarding = hasCompletedOnboarding,
                    healthConnectEnabled = healthConnectEnabled,
                    trainingDataSharingEnabled = trainingDataSharingEnabled,
                    downloadState = downloadState,
                )
            }.collect { bootstrap ->
                    _uiState.update { current ->
                        val formWithPreferenceChoices = if (!current.hasInitializedPreferenceChoices && bootstrap.hasCompletedOnboarding) {
                            current.form.copy(
                                healthConnectCaloriesEnabled = bootstrap.healthConnectEnabled,
                                trainingDataSharingEnabled = bootstrap.trainingDataSharingEnabled,
                            )
                        } else {
                            current.form
                        }
                        val populatedForm = if (bootstrap.profile != null && formWithPreferenceChoices.firstName.isBlank()) {
                            formWithPreferenceChoices.copy(
                                firstName = bootstrap.profile.firstName,
                                ageYears = bootstrap.profile.ageYears.toString(),
                                heightCm = bootstrap.profile.heightCm.toString(),
                                weightKg = bootstrap.profile.weightKg.toInt().toString(),
                                sex = bootstrap.profile.sex,
                                activityLevel = bootstrap.profile.activityLevel,
                            )
                        } else {
                            formWithPreferenceChoices
                        }

                        val estimatedBudget = populatedForm.estimatedBudgetOrNull(budgetCalculator)
                        val nextStep = when {
                            current.step == OnboardingStep.Downloading && modelStorage.hasUsableModel(ModelDescriptors.gemma) ->
                                OnboardingStep.Ready
                            current.step == OnboardingStep.BudgetPreview &&
                                modelStorage.hasUsableModel(ModelDescriptors.gemma) ->
                                OnboardingStep.Ready
                            else -> current.step
                        }

                        current.copy(
                            step = nextStep,
                            form = populatedForm,
                            estimatedBudgetCalories = estimatedBudget,
                            modelDownloadState = bootstrap.downloadState,
                            showCellularDownloadConfirmation = if (nextStep != current.step && nextStep == OnboardingStep.Ready) false else current.showCellularDownloadConfirmation,
                            hasInitializedPreferenceChoices = true,
                        )
                    }
                }
        }
    }

    fun updateForm(transform: (OnboardingFormState) -> OnboardingFormState) {
        _uiState.update { current ->
            val updatedForm = transform(current.form)
            val fieldErrors = if (current.hasAttemptedProfileSubmit) {
                OnboardingValidator.validateFields(updatedForm)
            } else {
                current.fieldErrors
            }
            current.copy(
                form = updatedForm,
                fieldErrors = fieldErrors,
                estimatedBudgetCalories = updatedForm.estimatedBudgetOrNull(budgetCalculator),
            )
        }
    }

    fun continueFromIntro() {
        _uiState.update { it.copy(step = OnboardingStep.Profile) }
    }

    fun backFromProfile() {
        _uiState.update {
            it.copy(
                step = OnboardingStep.DownloadIntro,
                fieldErrors = OnboardingValidator.FieldErrors(),
                hasAttemptedProfileSubmit = false,
            )
        }
    }

    fun submitProfile() {
        val fieldErrors = OnboardingValidator.validateFields(_uiState.value.form)
        if (fieldErrors.hasAny()) {
            _uiState.update {
                it.copy(
                    fieldErrors = fieldErrors,
                    hasAttemptedProfileSubmit = true,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    fieldErrors = OnboardingValidator.FieldErrors(),
                    hasAttemptedProfileSubmit = false,
                )
            }
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

    fun requestModelDownload() {
        if (modelStorage.hasUsableModel(ModelDescriptors.gemma)) {
            _uiState.update {
                it.copy(step = OnboardingStep.Ready, showCellularDownloadConfirmation = false)
            }
            return
        }
        if (_uiState.value.modelDownloadState.isDownloading) {
            _uiState.update {
                it.copy(step = OnboardingStep.Downloading, showCellularDownloadConfirmation = false)
            }
            return
        }
        when (networkConnectionMonitor.currentConnectionType()) {
            NetworkConnectionType.Cellular -> {
                _uiState.update { it.copy(showCellularDownloadConfirmation = true) }
            }
            else -> startModelDownload()
        }
    }

    fun confirmCellularModelDownload() {
        _uiState.update { it.copy(showCellularDownloadConfirmation = false) }
        startModelDownload()
    }

    fun dismissCellularModelDownloadConfirmation() {
        _uiState.update { it.copy(showCellularDownloadConfirmation = false) }
    }

    private fun startModelDownload() {
        modelDownloadController.enqueueIfNeeded(ModelDescriptors.gemma)
        _uiState.update {
            it.copy(
                step = if (modelStorage.hasUsableModel(ModelDescriptors.gemma)) OnboardingStep.Ready else OnboardingStep.Downloading,
                showCellularDownloadConfirmation = false,
            )
        }
    }

    fun completeSetup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleted = true) }
            val enabled = _uiState.value.form.healthConnectCaloriesEnabled
            val wasEnabled = preferences.healthConnectCaloriesEnabled.first()
            preferences.setHealthConnectCaloriesEnabled(enabled)
            if (enabled && !wasEnabled) {
                healthConnectBackfillService?.backfillRecentEntries()
            }
            val trainingEnabled = _uiState.value.form.trainingDataSharingEnabled
            preferences.setTrainingDataSharingEnabled(trainingEnabled)
            if (trainingEnabled) {
                modelImprovementUploadScheduler?.enablePeriodicSync()
                modelImprovementUploadScheduler?.enqueueImmediateSync()
                modelImprovementUploader?.uploadPendingIfEnabled()
            } else {
                modelImprovementUploadScheduler?.disablePeriodicSync()
            }
            preferences.setCompletedOnboarding(true)
        }
    }
}

private data class OnboardingBootstrapState(
    val profile: com.dreef3.weightlossapp.domain.model.UserProfile?,
    val hasCompletedOnboarding: Boolean,
    val healthConnectEnabled: Boolean,
    val trainingDataSharingEnabled: Boolean,
    val downloadState: ModelDownloadState,
)

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
