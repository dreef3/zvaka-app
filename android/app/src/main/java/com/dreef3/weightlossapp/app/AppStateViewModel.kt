package com.dreef3.weightlossapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.dreef3.weightlossapp.data.preferences.AppPreferences

data class AppState(
    val isReady: Boolean = false,
    val hasProfile: Boolean = false,
    val isSetupComplete: Boolean = false,
)

class AppStateViewModel(
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
    private val modelStorage: ModelStorage,
    private val modelDownloadController: ModelDownloadController,
) : ViewModel() {
    private val _ready = MutableStateFlow(true)

    val state: StateFlow<AppState> = combine(
        profileRepository.observeProfile(),
        preferences.hasCompletedOnboarding,
        modelDownloadController.observeState(ModelDescriptors.gemma),
    ) { profile, hasCompletedOnboarding, _: ModelDownloadState ->
            val hasGemmaModel = modelStorage.hasUsableModel(ModelDescriptors.gemma)
            AppState(
                isReady = _ready.value,
                hasProfile = profile != null,
                isSetupComplete = profile != null && hasCompletedOnboarding && hasGemmaModel,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppState(),
        )
}

class AppViewModelFactory(
    private val container: AppContainer,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(AppStateViewModel::class.java) ->
                AppStateViewModel(
                    container.profileRepository,
                    container.preferences,
                    container.modelStorage,
                    container.modelDownloadRepository,
                ) as T
            else -> error("Unsupported ViewModel: ${modelClass.name}")
        }
    }
}
