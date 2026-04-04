package com.dreef3.weightlossapp.app

import android.util.Log
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object AppInitializer {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var started = false

    fun initialize(container: AppContainer) {
        if (started) return
        started = true
        executor.execute {
            runCatching {
                container.photoStorage.ensureDirectories()
                container.modelStorage.modelDirectory.mkdirs()
                container.modelStorage.cleanupIncompleteModelFiles(ModelDescriptors.gemma)
                container.modelStorage.logState(tag = TAG, model = ModelDescriptors.gemma)
                runBlocking { container.preferences.setCalorieEstimationModel(CalorieEstimationModel.Gemma) }
                val onboardingComplete = runBlocking { container.preferences.hasCompletedOnboarding.first() }
                if (onboardingComplete &&
                    !container.modelStorage.hasUsableModel(ModelDescriptors.gemma) &&
                    container.networkConnectionMonitor.currentConnectionType() == NetworkConnectionType.Wifi
                ) {
                    container.modelDownloadRepository.enqueueIfNeeded(ModelDescriptors.gemma)
                    Log.i(TAG, "Scheduled Gemma background download on Wi-Fi")
                }
                if (!container.modelStorage.hasUsableModel(ModelDescriptors.gemma)) {
                    Log.i(TAG, "Skipped model warm-up because Gemma is not ready yet")
                    return@runCatching
                }
                Log.i(TAG, "Skipping startup Gemma warm-up for now")
            }
        }
    }

    private const val TAG = "AppInitializer"
}
