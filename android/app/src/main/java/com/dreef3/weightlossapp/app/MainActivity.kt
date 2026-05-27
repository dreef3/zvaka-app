package com.dreef3.weightlossapp.app

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.ui.theme.WeightLossAppTheme
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.chat.DietChatSnapshot
import com.dreef3.weightlossapp.chat.requiredPhotoModelDescriptors
import com.dreef3.weightlossapp.chat.requiredModelDescriptor
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import android.graphics.Bitmap
import android.graphics.Color
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface NpuSmokeTestStatus {
    data object NotRunning : NpuSmokeTestStatus
    data object Running : NpuSmokeTestStatus
    data class Failed(val error: String) : NpuSmokeTestStatus
    data object Succeeded : NpuSmokeTestStatus
}

object NpuSmokeTestState {
    val status = MutableStateFlow<NpuSmokeTestStatus>(NpuSmokeTestStatus.NotRunning)
}

sealed interface GpuSmokeTestStatus {
    data object NotRunning : GpuSmokeTestStatus
    data object Running : GpuSmokeTestStatus
    data class Failed(val error: String) : GpuSmokeTestStatus
    data object Succeeded : GpuSmokeTestStatus
}

object GpuSmokeTestState {
    val coachStatus = MutableStateFlow<GpuSmokeTestStatus>(GpuSmokeTestStatus.NotRunning)
    val foodEstimationStatus = MutableStateFlow<GpuSmokeTestStatus>(GpuSmokeTestStatus.NotRunning)
}

class MainActivity : ComponentActivity() {
    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appUpdateManager: AppUpdateManager
    private var hasCheckedForImmediateUpdate = false
    private var shouldCheckForImmediateUpdate = false
    private val immediateUpdateOptions = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
    private val immediateUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Log.w(TAG, "Immediate update flow ended with resultCode=${result.resultCode}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shouldCheckForImmediateUpdate = savedInstanceState == null && isLauncherStart(intent)
        AppContainer.initialize(applicationContext)
        AppInitializer.initialize(AppContainer.instance)
        handleIntentActions(intent, fromNewIntent = false)
        maybeRunDebugCoachActions(intent)
        maybeRunCoachNpuSmokeTest(intent)
        maybeRunCoachGpuSmokeTest(intent)
        maybeRunFoodEstimationGpuSmokeTest(intent)
        appUpdateManager = AppUpdateManagerFactory.create(applicationContext)
        setContent {
            WeightLossAppTheme {
                WeightLossRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!BuildConfig.DEBUG && shouldCheckForImmediateUpdate && !hasCheckedForImmediateUpdate) {
            hasCheckedForImmediateUpdate = true
            checkForImmediateUpdate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentActions(intent, fromNewIntent = true)
        maybeRunDebugCoachActions(intent)
        maybeRunCoachNpuSmokeTest(intent)
        maybeRunCoachGpuSmokeTest(intent)
        maybeRunFoodEstimationGpuSmokeTest(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) {
            resumeImmediateUpdateIfNeeded()
        }
    }

    private fun checkForImmediateUpdate() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    appUpdateInfo.isUpdateTypeAllowed(immediateUpdateOptions)
                ) {
                    launchImmediateUpdate(appUpdateInfo)
                }
            }
            .addOnFailureListener { throwable ->
                Log.w(TAG, "Failed checking for immediate app update", throwable)
            }
    }

    private fun resumeImmediateUpdateIfNeeded() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    launchImmediateUpdate(appUpdateInfo)
                }
            }
            .addOnFailureListener { throwable ->
                Log.w(TAG, "Failed resuming immediate app update", throwable)
            }
    }

    private fun launchImmediateUpdate(appUpdateInfo: AppUpdateInfo) {
        runCatching {
            appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo,
                immediateUpdateLauncher,
                immediateUpdateOptions,
            )
        }.onSuccess { started ->
            if (!started) {
                Log.w(TAG, "Immediate update flow was not started")
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Immediate update flow launch failed", throwable)
        }
    }

    private fun isLauncherStart(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }

    private fun handleIntentActions(intent: Intent?, fromNewIntent: Boolean) {
        if (intent?.getBooleanExtra(EXTRA_RETRIGGER_RECENT_MODEL_UPLOADS, false) != true) {
            return
        }
        val recentDays = intent.getIntExtra(EXTRA_RECENT_MODEL_UPLOAD_DAYS, DEFAULT_RECENT_MODEL_UPLOAD_DAYS)
            .coerceIn(1, MAX_RECENT_MODEL_UPLOAD_DAYS)
        lifecycleScope.launch {
            val cutoff = Instant.now().minusSeconds(recentDays.toLong() * SECONDS_PER_DAY)
            val container = AppContainer.instance
            val resetCount = container.foodEntryRepository.resetModelImprovementUploadsSince(cutoff)
            Log.i(
                TAG,
                "Retriggering model improvement uploads for recentDays=$recentDays resetCount=$resetCount fromNewIntent=$fromNewIntent",
            )
            runCatching {
                container.modelImprovementUploadScheduler.enqueueImmediateSync()
            }.onSuccess {
                Log.i(TAG, "Model improvement upload retrigger enqueued for resetCount=$resetCount")
            }.onFailure { throwable ->
                Log.e(TAG, "Model improvement upload retrigger failed for resetCount=$resetCount", throwable)
            }
        }
    }

    private fun maybeRunCoachNpuSmokeTest(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.getBooleanExtra(EXTRA_RUN_COACH_NPU_SMOKE_TEST, false) != true) {
            return
        }
        NpuSmokeTestState.status.value = NpuSmokeTestStatus.Running
        debugScope.launch {
            val result = AppContainer.instance.selectedCoachNpuSmokeTestEngine.sendMessage(
                message = "Reply with exactly OK.",
                history = emptyList(),
                snapshot = DietChatSnapshot(
                    todayBudgetCalories = null,
                    todayConsumedCalories = 0,
                    todayRemainingCalories = null,
                    entries = emptyList(),
                ),
            )
            result
                .onSuccess { reply ->
                    Log.i(TAG, "Coach NPU smoke test succeeded: ${reply.take(120)}")
                    NpuSmokeTestState.status.value = NpuSmokeTestStatus.Succeeded
                }
                .onFailure { throwable ->
                    Log.e(TAG, "Coach NPU smoke test failed", throwable)
                    NpuSmokeTestState.status.value = NpuSmokeTestStatus.Failed(
                        throwable.message?.take(200) ?: "unknown error",
                    )
                }
        }
    }

    private fun maybeRunCoachGpuSmokeTest(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.getBooleanExtra(EXTRA_RUN_COACH_GPU_SMOKE_TEST, false) != true) {
            return
        }
        GpuSmokeTestState.coachStatus.value = GpuSmokeTestStatus.Running
        debugScope.launch {
            val result = AppContainer.instance.selectedCoachGpuSmokeTestEngine.sendMessage(
                message = "Reply with exactly OK.",
                history = emptyList(),
                snapshot = DietChatSnapshot(
                    todayBudgetCalories = null,
                    todayConsumedCalories = 0,
                    todayRemainingCalories = null,
                    entries = emptyList(),
                ),
            )
            result
                .onSuccess { reply ->
                    Log.i(TAG, "Coach GPU smoke test succeeded: ${reply.take(120)}")
                    GpuSmokeTestState.coachStatus.value = GpuSmokeTestStatus.Succeeded
                }
                .onFailure { throwable ->
                    Log.e(TAG, "Coach GPU smoke test failed", throwable)
                    GpuSmokeTestState.coachStatus.value = GpuSmokeTestStatus.Failed(
                        throwable.message?.take(200) ?: "unknown error",
                    )
                }
        }
    }

    private fun maybeRunFoodEstimationGpuSmokeTest(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.getBooleanExtra(EXTRA_RUN_FOOD_ESTIMATION_GPU_SMOKE_TEST, false) != true) {
            return
        }
        GpuSmokeTestState.foodEstimationStatus.value = GpuSmokeTestStatus.Running
        debugScope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "smoke_test_image.jpg")
                val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.rgb(180, 120, 60))
                FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bmp.recycle()
                f
            }
            val result = AppContainer.instance.foodEstimationGpuSmokeTestEngine.estimate(
                FoodEstimationRequest(
                    imagePath = tempFile.absolutePath,
                    capturedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            result
                .onSuccess { estimation ->
                    Log.i(
                        TAG,
                        "Food estimation GPU smoke test succeeded: " +
                            "${estimation.detectedFoodLabel} ~${estimation.estimatedCalories} kcal",
                    )
                    GpuSmokeTestState.foodEstimationStatus.value = GpuSmokeTestStatus.Succeeded
                }
                .onFailure { throwable ->
                    Log.e(TAG, "Food estimation GPU smoke test failed", throwable)
                    GpuSmokeTestState.foodEstimationStatus.value = GpuSmokeTestStatus.Failed(
                        throwable.message?.take(200) ?: "unknown error",
                    )
                }
        }
    }

    private fun maybeRunDebugCoachActions(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val requestedCoachModel = intent.getStringExtra(EXTRA_SET_COACH_MODEL_STORAGE_KEY)
            ?.let(CoachModel::fromStorageKey)
        val retryFoodEntryId = intent.getLongExtra(EXTRA_RETRY_FOOD_ENTRY_ID, 0L)
            .takeIf { it > 0L }
        val shouldDownloadSelectedModel = intent.getBooleanExtra(EXTRA_DOWNLOAD_SELECTED_COACH_MODEL, false)
        val shouldDownloadSelectedPhotoModels = intent.getBooleanExtra(EXTRA_DOWNLOAD_SELECTED_PHOTO_MODELS, false)
        val shouldRunSelectedSmokeTest = intent.getBooleanExtra(EXTRA_RUN_SELECTED_COACH_SMOKE_TEST, false)
        val shouldRunSelectedDoubleSmokeTest = intent.getBooleanExtra(EXTRA_RUN_SELECTED_COACH_DOUBLE_SMOKE_TEST, false)
        if (requestedCoachModel == null &&
            retryFoodEntryId == null &&
            !shouldDownloadSelectedModel &&
            !shouldDownloadSelectedPhotoModels &&
            !shouldRunSelectedSmokeTest &&
            !shouldRunSelectedDoubleSmokeTest
        ) {
            return
        }

        debugScope.launch {
            val container = AppContainer.instance
            val coachModel = requestedCoachModel ?: container.preferences.readCoachModel()
            if (requestedCoachModel != null) {
                container.preferences.setCoachModel(coachModel)
                Log.i(TAG, "Debug coach action set coachModel=${coachModel.storageKey}")
            }

            if (retryFoodEntryId != null) {
                val entry = container.foodEntryRepository.getEntry(retryFoodEntryId)
                if (entry == null) {
                    Log.e(TAG, "Debug retry failed: entryId=$retryFoodEntryId not found")
                } else {
                    container.backgroundPhotoCaptureUseCase.retry(entry)
                    Log.i(TAG, "Debug retry queued entryId=$retryFoodEntryId path=${entry.imagePath}")
                }
            }

            val descriptor = coachModel.requiredModelDescriptor()
            if (shouldDownloadSelectedModel) {
                container.modelDownloader.downloadFrom(descriptor) { downloadedBytes, totalBytes ->
                    val percent = if (totalBytes > 0L) (downloadedBytes * 100L) / totalBytes else 0L
                    Log.i(
                        TAG,
                        "Debug coach download ${descriptor.fileName}: ${percent}% (${downloadedBytes}/${totalBytes})",
                    )
                }
                    .onSuccess {
                        Log.i(TAG, "Debug coach download complete path=${it.absolutePath} bytes=${it.length()}")
                    }
                    .onFailure { throwable ->
                        Log.e(TAG, "Debug coach download failed for ${descriptor.fileName}", throwable)
                    }
            }

            if (shouldDownloadSelectedPhotoModels) {
                coachModel.requiredPhotoModelDescriptors().forEach { photoDescriptor ->
                    container.modelDownloader.downloadFrom(photoDescriptor) { downloadedBytes, totalBytes ->
                        val percent = if (totalBytes > 0L) (downloadedBytes * 100L) / totalBytes else 0L
                        Log.i(
                            TAG,
                            "Debug photo-model download ${photoDescriptor.fileName}: ${percent}% (${downloadedBytes}/${totalBytes})",
                        )
                    }
                        .onSuccess {
                            Log.i(TAG, "Debug photo-model download complete path=${it.absolutePath} bytes=${it.length()}")
                        }
                        .onFailure { throwable ->
                            Log.e(TAG, "Debug photo-model download failed for ${photoDescriptor.fileName}", throwable)
                        }
                }
            }

            if (shouldRunSelectedSmokeTest || shouldRunSelectedDoubleSmokeTest) {
                runSelectedCoachSmokeTestOnce(container, "cold")
            }
            if (shouldRunSelectedDoubleSmokeTest) {
                runSelectedCoachSmokeTestOnce(container, "warm")
            }
        }
    }

    private suspend fun runSelectedCoachSmokeTestOnce(container: AppContainer, label: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val result = container.dietChatEngine.sendMessage(
            message = "Reply with exactly OK.",
            history = emptyList(),
            snapshot = DietChatSnapshot(
                todayBudgetCalories = null,
                todayConsumedCalories = 0,
                todayRemainingCalories = null,
                entries = emptyList(),
            ),
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        result
            .onSuccess { reply ->
                Log.i(TAG, "Selected coach smoke test succeeded [$label] in ${elapsedMs}ms: ${reply.take(120)}")
            }
            .onFailure { throwable ->
                Log.e(TAG, "Selected coach smoke test failed [$label] after ${elapsedMs}ms", throwable)
            }
    }

    private companion object {
        const val EXTRA_SET_COACH_MODEL_STORAGE_KEY = "setCoachModelStorageKey"
        const val EXTRA_RETRY_FOOD_ENTRY_ID = "retryFoodEntryId"
        const val EXTRA_DOWNLOAD_SELECTED_COACH_MODEL = "downloadSelectedCoachModel"
        const val EXTRA_DOWNLOAD_SELECTED_PHOTO_MODELS = "downloadSelectedPhotoModels"
        const val EXTRA_RUN_COACH_NPU_SMOKE_TEST = "runCoachNpuSmokeTest"
        const val EXTRA_RUN_COACH_GPU_SMOKE_TEST = "runCoachGpuSmokeTest"
        const val EXTRA_RUN_FOOD_ESTIMATION_GPU_SMOKE_TEST = "runFoodEstimationGpuSmokeTest"
        const val EXTRA_RUN_SELECTED_COACH_DOUBLE_SMOKE_TEST = "runSelectedCoachDoubleSmokeTest"
        const val EXTRA_RUN_SELECTED_COACH_SMOKE_TEST = "runSelectedCoachSmokeTest"
        const val TAG = "MainActivity"
        const val EXTRA_RETRIGGER_RECENT_MODEL_UPLOADS = "retrigger_recent_model_uploads"
        const val EXTRA_RECENT_MODEL_UPLOAD_DAYS = "recent_model_upload_days"
        private const val DEFAULT_RECENT_MODEL_UPLOAD_DAYS = 7
        private const val MAX_RECENT_MODEL_UPLOAD_DAYS = 30
        private const val SECONDS_PER_DAY = 86_400L
    }
}
