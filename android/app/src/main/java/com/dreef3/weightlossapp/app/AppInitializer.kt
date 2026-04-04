package com.dreef3.weightlossapp.app

import android.util.Log
import com.dreef3.weightlossapp.app.di.AppContainer
import java.util.concurrent.Executors
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
                container.modelStorage.cleanupIncompleteModelFiles()
                container.modelStorage.logState()
                if (!container.modelStorage.hasUsableModel()) {
                    container.modelDownloadRepository.enqueueIfNeeded()
                    Log.i(TAG, "Scheduled model download from Hugging Face")
                    return@runCatching
                }
                val warmUpResult = runBlocking {
                    container.foodEstimationEngine.warmUp()
                }
                if (warmUpResult.isSuccess) {
                    Log.i(TAG, "Startup model warm-up succeeded")
                } else {
                    Log.w(TAG, "Startup model warm-up failed", warmUpResult.exceptionOrNull())
                }
            }
        }
    }

    private const val TAG = "AppInitializer"
}
