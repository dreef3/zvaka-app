package com.dreef3.weightlossapp.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dreef3.weightlossapp.app.di.AppContainer

class OnboardingViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OnboardingViewModel(container) as T
    }
}
