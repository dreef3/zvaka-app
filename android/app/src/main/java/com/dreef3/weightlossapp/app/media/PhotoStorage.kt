package com.dreef3.weightlossapp.app.media

import android.content.Context
import java.io.File
import java.time.Instant

class PhotoStorage(
    private val context: Context,
) {
    fun createPhotoFile(): File {
        val directory = File(context.filesDir, "photos").apply { mkdirs() }
        return File(directory, "meal-${Instant.now().toEpochMilli()}.jpg")
    }

    fun ensureDirectories() {
        File(context.filesDir, "photos").mkdirs()
    }
}
