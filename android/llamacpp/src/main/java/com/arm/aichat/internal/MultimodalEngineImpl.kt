package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.MultimodalEngine
import dalvik.annotation.optimization.FastNative
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MultimodalEngineImpl private constructor(
    private val nativeLibDir: String,
) : MultimodalEngine {
    companion object {
        private val TAG = MultimodalEngineImpl::class.java.simpleName

        @Volatile
        private var instance: MultimodalEngine? = null

        internal fun getInstance(context: Context): MultimodalEngine =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path." }
                MultimodalEngineImpl(nativeLibDir).also { instance = it }
            }
    }

    @FastNative
    private external fun init(nativeLibDir: String)

    @FastNative
    private external fun load(modelPath: String, mmprojPath: String): Int

    @FastNative
    private external fun nativeAnalyzeImage(prompt: String, imagePath: String, predictLength: Int): String

    @FastNative
    private external fun unload()

    @FastNative
    private external fun shutdown()

    private val mutex = Mutex()
    private var loadedModelPath: String? = null
    private var loadedMmprojPath: String? = null

    init {
        System.loadLibrary("ai-chat")
        init(nativeLibDir)
    }

    override suspend fun loadModel(modelPath: String, mmprojPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) {
                return@withLock
            }
            require(File(modelPath).exists()) { "Model file not found: $modelPath" }
            require(File(mmprojPath).exists()) { "mmproj file not found: $mmprojPath" }
            val result = load(modelPath, mmprojPath)
            check(result == 0) { "Failed to load multimodal model, error=$result" }
            loadedModelPath = modelPath
            loadedMmprojPath = mmprojPath
            Log.i(TAG, "Loaded multimodal model: $modelPath with mmproj: $mmprojPath")
        }
    }

    override suspend fun analyzeImage(
        prompt: String,
        imagePath: String,
        predictLength: Int,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loadedModelPath != null && loadedMmprojPath != null) { "Multimodal model is not loaded." }
            nativeAnalyzeImage(prompt, imagePath, predictLength)
        }
    }

    override fun cleanUp() {
        loadedModelPath = null
        loadedMmprojPath = null
        unload()
    }

    override fun destroy() {
        cleanUp()
        shutdown()
    }
}
