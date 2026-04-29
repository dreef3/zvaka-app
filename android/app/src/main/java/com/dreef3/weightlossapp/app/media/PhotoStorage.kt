package com.dreef3.weightlossapp.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

open class PhotoStorage(
    private val context: Context,
) {
    private val photoDirectory: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun createPhotoFile(): File {
        return File(photoDirectory, "meal-${Instant.now().toEpochMilli()}.jpg")
    }

    fun listPhotoFiles(): List<File> = photoDirectory.listFiles()?.filter(File::isFile).orEmpty()

    open fun isReadablePhoto(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    open fun normalizePhoto(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.isFile) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, NORMALIZED_MAX_DIMENSION_PX)
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return false

        val normalizedBitmap = if (bitmap.width > NORMALIZED_MAX_DIMENSION_PX || bitmap.height > NORMALIZED_MAX_DIMENSION_PX) {
            val scale = minOf(
                NORMALIZED_MAX_DIMENSION_PX.toFloat() / bitmap.width.toFloat(),
                NORMALIZED_MAX_DIMENSION_PX.toFloat() / bitmap.height.toFloat(),
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        val bytes = ByteArrayOutputStream().use { stream ->
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, NORMALIZED_JPEG_QUALITY, stream)
            normalizedBitmap.recycle()
            stream.toByteArray()
        }
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeBytes(bytes)
        if (file.exists() && !file.delete()) {
            tempFile.delete()
            return false
        }
        if (!tempFile.renameTo(file)) {
            tempFile.delete()
            return false
        }
        return true
    }

    fun ensureDirectories() {
        photoDirectory.mkdirs()
    }

    fun clearAll() {
        photoDirectory.listFiles()?.forEach { file ->
            file.delete()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxDimensionPx * 2 || currentHeight > maxDimensionPx * 2) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample
    }

    private companion object {
        const val NORMALIZED_MAX_DIMENSION_PX = 1600
        const val NORMALIZED_JPEG_QUALITY = 85
    }
}
