package com.dreef3.weightlossapp.app

import android.os.Bundle
import android.util.Log
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.ui.theme.WeightLossAppTheme
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

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

    private companion object {
        const val TAG = "MainActivity"
    }
}
