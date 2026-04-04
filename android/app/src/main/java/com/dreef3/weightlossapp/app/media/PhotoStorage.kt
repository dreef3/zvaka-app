package com.dreef3.weightlossapp.app.media

import android.content.Context
import java.io.File
import java.time.Instant

class PhotoStorage(
    private val context: Context,
) {
    private val photoDirectory: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun createPhotoFile(): File {
        return File(photoDirectory, "meal-${Instant.now().toEpochMilli()}.jpg")
    }

    fun ensureDirectories() {
        photoDirectory.mkdirs()
    }

    fun clearAll() {
        photoDirectory.listFiles()?.forEach { file ->
            file.delete()
        }
    }
}
