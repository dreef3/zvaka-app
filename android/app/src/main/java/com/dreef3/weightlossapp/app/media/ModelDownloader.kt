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
    open suspend fun downloadFrom(
        model: ModelDescriptor = ModelDescriptors.gemma,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val destination = modelStorage.fileFor(model)
            val tempFile = File(destination.absolutePath + ".part")
            modelStorage.modelDirectory.mkdirs()

            Log.i(
                TAG,
                "Starting model download on thread=${Thread.currentThread().name} from ${model.url} to ${destination.absolutePath}",
            )

            val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                doInput = true
            }

            val resumedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
            if (resumedBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$resumedBytes-")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()

            if (connection.responseCode !in 200..299 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val totalBytes = when {
                connection.getHeaderField("Content-Range") != null -> {
                    connection.getHeaderField("Content-Range")
                        ?.substringAfterLast("/")
                        ?.toLongOrNull()
                        ?: model.totalBytes
                }
                connection.contentLengthLong > 0L -> connection.contentLengthLong + resumedBytes
                else -> model.totalBytes
            }

            var downloadedBytes = resumedBytes
            var lastProgressTs = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile, resumedBytes > 0L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTs >= 200) {
                            onProgress?.invoke(downloadedBytes, totalBytes)
                            lastProgressTs = now
                        }
                    }
                }
            }
            onProgress?.invoke(downloadedBytes, totalBytes)

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
