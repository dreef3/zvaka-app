package com.dreef3.weightlossapp.app.media

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class ModelDownloader(
    private val modelStorage: ModelStorage,
) {
    open suspend fun downloadFrom(url: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val destination = modelStorage.defaultModelFile
            val tempFile = File(destination.absolutePath + ".part")
            modelStorage.modelDirectory.mkdirs()

            Log.i(
                TAG,
                "Starting model download on thread=${Thread.currentThread().name} from $url to ${destination.absolutePath}",
            )

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                doInput = true
                connect()
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }

            if (destination.exists()) {
                destination.delete()
            }
            if (!tempFile.renameTo(destination)) {
                throw IllegalStateException("Could not move temp model file into place")
            }

            Log.i(TAG, "Model download complete size=${destination.length()}")
            destination
        }.onFailure { error ->
            Log.e(TAG, "Model download failed", error)
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
