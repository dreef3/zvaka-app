package com.dreef3.weightlossapp.app

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.dreef3.weightlossapp.app.widget.HomeStatusWidgetUpdater
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.chat.requiredModelDescriptor
import com.dreef3.weightlossapp.inference.requiredModelDescriptors
import java.time.Instant
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
                normalizeStoredPhotosIfNeeded(container)
                recoverStalePhotoProcessingEntries(container)
                container.modelStorage.logState(tag = TAG, model = ModelDescriptors.gemma)
                val driveSyncEnabled = runBlocking { container.preferences.readDriveSyncState().isEnabled }
                val trainingDataSharingEnabled = runBlocking { container.preferences.trainingDataSharingEnabled.first() }
                val selectedCoachModel = runBlocking { container.preferences.readCoachModel() }
                val selectedCalorieModel = runBlocking { container.preferences.readCalorieEstimationModel() }
                if (driveSyncEnabled) {
                    container.driveSyncScheduler.enablePeriodicSync()
                } else {
                    container.driveSyncScheduler.disablePeriodicSync()
                }
                if (trainingDataSharingEnabled && container.modelImprovementUploader.isUploadAvailable()) {
                    container.modelImprovementUploadScheduler.enablePeriodicSync()
                    container.modelImprovementUploadScheduler.enqueueImmediateSync()
                    runCatching {
                        runBlocking { container.modelImprovementUploader.uploadPendingIfEnabled() }
                    }.onFailure { error ->
                        debugLog("Startup model improvement upload sync failed: ${error.message}")
                    }
                } else {
                    container.modelImprovementUploadScheduler.disablePeriodicSync()
                }
                val onboardingComplete = runBlocking { container.preferences.hasCompletedOnboarding.first() }
                if (onboardingComplete &&
                    container.networkConnectionMonitor.currentConnectionType() == NetworkConnectionType.Wifi
                ) {
                    if (!container.modelStorage.hasUsableModel(selectedCoachModel.requiredModelDescriptor())) {
                        container.modelDownloadRepository.enqueueIfNeeded(selectedCoachModel.requiredModelDescriptor())
                        debugLog("Scheduled ${selectedCoachModel.displayName} background download on Wi-Fi")
                    }
                    selectedCalorieModel.requiredModelDescriptors().forEach { descriptor ->
                        if (!container.modelStorage.hasUsableModel(descriptor)) {
                            container.modelDownloadRepository.enqueueIfNeeded(descriptor)
                        }
                    }
                }
                if (!container.modelStorage.hasUsableModel(ModelDescriptors.gemma)) {
                    debugLog("Skipped model warm-up because Gemma is not ready yet")
                    return@runCatching
                }
                debugLog("Skipping startup Gemma warm-up for now")
            }
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.i(TAG, message)
        }
    }

    private fun recoverStalePhotoProcessingEntries(container: AppContainer) {
        val cutoffEpochMs = Instant.now().minusSeconds(STALE_PROCESSING_TIMEOUT_SECONDS).toEpochMilli()
        val foodEntryDao = container.database.foodEntryDao()
        runBlocking {
            val staleEntries = foodEntryDao.getAll().filter { entry ->
                entry.entryStatus == FoodEntryStatus.Processing.name &&
                    entry.deletedAtEpochMs == null &&
                    entry.capturedAtEpochMs < cutoffEpochMs
            }
            staleEntries.forEach { entry ->
                foodEntryDao.update(
                    entry.copy(
                        estimatedCalories = 0,
                        finalCalories = 0,
                        confidenceState = "Failed",
                        detectedFoodLabel = null,
                        confidenceNotes = "Photo saved. Background processing stopped before finishing, so calories need manual entry.",
                        entryStatus = FoodEntryStatus.NeedsManual.name,
                    ),
                )
            }
            if (staleEntries.isNotEmpty()) {
                debugLog("Recovered ${staleEntries.size} stale processing photo entries")
                HomeStatusWidgetUpdater.requestRefresh(container.appContext)
            }
        }
    }

    private fun normalizeStoredPhotosIfNeeded(container: AppContainer) {
        val alreadyCompleted = runBlocking {
            container.preferences.hasCompletedPhotoNormalizationMigration.first()
        }
        if (alreadyCompleted) return

        val photoFiles = container.photoStorage.listPhotoFiles()
        var normalizedCount = 0
        photoFiles.forEach { file ->
            runCatching {
                if (container.photoStorage.normalizePhoto(file.absolutePath)) {
                    normalizedCount += 1
                }
            }.onFailure { error ->
                debugLog("Photo normalization failed for ${file.name}: ${error.message}")
            }
        }
        runBlocking {
            container.preferences.setPhotoNormalizationMigrationCompleted(true)
        }
        if (normalizedCount > 0) {
            debugLog("Normalized $normalizedCount stored meal photos")
        }
    }

    private const val TAG = "AppInitializer"
    private const val STALE_PROCESSING_TIMEOUT_SECONDS = 180L
}
