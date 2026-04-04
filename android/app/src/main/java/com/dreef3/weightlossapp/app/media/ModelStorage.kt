package com.dreef3.weightlossapp.app.media

import android.content.Context
import android.util.Log
import java.io.File

class ModelStorage(
    private val context: Context? = null,
    private val modelDirectoryOverride: File? = null,
) {
    val modelDirectory: File
        get() = (
            modelDirectoryOverride
                ?: File(requireNotNull(context).filesDir, "models")
            ).apply { mkdirs() }

    val defaultModelFile: File
        get() = File(modelDirectory, DEFAULT_MODEL_FILE_NAME)

    fun hasUsableModel(): Boolean = defaultModelFile.exists() && defaultModelFile.length() > 0L

    fun cleanupIncompleteModelFiles() {
        val model = defaultModelFile
        if (model.exists() && model.length() == 0L) {
            model.delete()
        }
        val partial = File(modelDirectory, "${DEFAULT_MODEL_FILE_NAME}.part")
        if (partial.exists()) {
            partial.delete()
        }
    }

    fun clearAll() {
        modelDirectory.listFiles()?.forEach { file ->
            file.delete()
        }
    }

    fun logState(tag: String = "ModelStorage") {
        val directory = modelDirectory
        val model = defaultModelFile
        Log.i(
            tag,
            "modelDirectory=${directory.absolutePath} exists=${directory.exists()} canRead=${directory.canRead()} canWrite=${directory.canWrite()}",
        )
        Log.i(
            tag,
            "defaultModelFile=${model.absolutePath} exists=${model.exists()} canRead=${model.canRead()} length=${model.length()}",
        )
        val children = directory.listFiles()
            ?.joinToString(prefix = "[", postfix = "]") { file ->
                "${file.name}(exists=${file.exists()},size=${file.length()})"
            }
            ?: "null"
        Log.i(tag, "modelDirectoryChildren=$children")
    }

    companion object {
        const val DEFAULT_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    }
}
