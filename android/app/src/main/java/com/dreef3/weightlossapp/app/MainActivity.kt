package com.dreef3.weightlossapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.ui.theme.WeightLossAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.initialize(applicationContext)
        AppInitializer.initialize(AppContainer.instance)
        setContent {
            WeightLossAppTheme {
                WeightLossRoot()
            }
        }
    }
}
