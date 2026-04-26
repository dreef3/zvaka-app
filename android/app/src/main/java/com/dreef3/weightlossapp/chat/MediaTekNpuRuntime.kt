package com.dreef3.weightlossapp.chat

import android.util.Log
import com.dreef3.weightlossapp.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaTekNpuRuntime {
    private const val TAG = "MediaTekNpuRuntime"
    private val nativeLoadAttempted = AtomicBoolean(false)

    fun prepare(nativeLibraryDir: String) {
        if (!nativeLoadAttempted.compareAndSet(false, true)) return

        preload(nativeLibraryDir, "libLiteRt.so", "LiteRt", "LiteRT runtime")
        preload(nativeLibraryDir, "libLiteRtMtkPropShim.so", "LiteRtMtkPropShim", "MediaTek property shim")
    }

    private fun preload(
        nativeLibraryDir: String,
        libraryFileName: String,
        libraryName: String,
        label: String,
    ) {
        val libraryPath = nativeLibraryDir.takeIf { it.isNotBlank() }
            ?.let { File(it, libraryFileName) }

        runCatching {
            when {
                libraryPath != null && libraryPath.exists() -> {
                    System.load(libraryPath.absolutePath)
                    debugLog("Loaded $label from ${libraryPath.absolutePath}")
                }
                else -> {
                    System.loadLibrary(libraryName)
                    debugLog("Loaded $label via System.loadLibrary")
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to preload $label", error)
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}
