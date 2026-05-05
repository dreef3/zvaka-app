package com.dreef3.weightlossapp.app.widget

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dreef3.weightlossapp.app.di.AppContainer
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WidgetCameraActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPhotoPath: String? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCapture() else finish()
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val path = pendingPhotoPath
        pendingPhotoPath = null
        if (saved && path != null) {
            scope.launch {
                runCatching {
                    AppContainer.instance.backgroundPhotoCaptureUseCase.enqueue(
                        imagePath = path,
                        capturedAt = Instant.now(),
                    )
                }.onFailure { Log.e(TAG, "Failed to enqueue widget photo", it) }
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.initialize(applicationContext)
        if (savedInstanceState != null) return
        if (hasCameraPermission()) {
            startCapture()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        val container = AppContainer.instance
        val file = container.photoStorage.createPhotoFile()
        pendingPhotoPath = file.absolutePath
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePictureLauncher.launch(uri)
    }

    private companion object {
        private const val TAG = "WidgetCameraActivity"
    }
}
