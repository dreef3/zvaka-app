package com.dreef3.weightlossapp.app

import android.os.Bundle
import android.util.Log
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.ui.theme.WeightLossAppTheme
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import java.time.Instant
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) {
            resumeImmediateUpdateIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentActions(intent, fromNewIntent = true)
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

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_RETRIGGER_RECENT_MODEL_UPLOADS = "retrigger_recent_model_uploads"
        const val EXTRA_RECENT_MODEL_UPLOAD_DAYS = "recent_model_upload_days"
        private const val DEFAULT_RECENT_MODEL_UPLOAD_DAYS = 7
        private const val MAX_RECENT_MODEL_UPLOAD_DAYS = 30
        private const val SECONDS_PER_DAY = 86_400L
    }
}
