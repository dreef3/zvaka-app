package com.dreef3.weightlossapp.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer

@Composable
fun WeightLossRoot() {
    val appStateViewModel: AppStateViewModel = viewModel(
        factory = AppViewModelFactory(AppContainer.instance),
    )
    AppNavHost(appStateViewModel = appStateViewModel)
}
