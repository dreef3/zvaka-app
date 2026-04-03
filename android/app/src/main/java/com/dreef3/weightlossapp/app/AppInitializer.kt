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
                while (!container.modelStorage.hasUsableModel()) {
                    val result = runBlocking {
                        container.modelDownloader.downloadFrom(DEBUG_MODEL_URL)
                    }
                    if (result.isSuccess) {
                        Log.i(TAG, "Startup model download succeeded")
                        break
                    }
                    Log.w(TAG, "Startup model download failed, retrying in ${RETRY_DELAY_MS}ms", result.exceptionOrNull())
                    Thread.sleep(RETRY_DELAY_MS)
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
    private const val DEBUG_MODEL_URL = "http://192.168.0.168:18080/gemma-4-E2B-it.litertlm"
    private const val RETRY_DELAY_MS = 5_000L
}
