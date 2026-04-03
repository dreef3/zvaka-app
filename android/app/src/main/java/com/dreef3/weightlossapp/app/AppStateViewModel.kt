package com.dreef3.weightlossapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AppState(
    val isReady: Boolean = false,
    val hasProfile: Boolean = false,
)

class AppStateViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _ready = MutableStateFlow(true)

    val state: StateFlow<AppState> = profileRepository.observeProfile()
        .map { profile ->
            AppState(
                isReady = _ready.value,
                hasProfile = profile != null,
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
                AppStateViewModel(container.profileRepository) as T
            else -> error("Unsupported ViewModel: ${modelClass.name}")
        }
    }
}
