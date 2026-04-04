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
        get() = fileFor(ModelDescriptors.gemma)

    fun fileFor(model: ModelDescriptor): File = File(modelDirectory, model.fileName)

    fun hasUsableModel(model: ModelDescriptor = ModelDescriptors.gemma): Boolean =
        fileFor(model).exists() && fileFor(model).length() > 0L

    fun cleanupIncompleteModelFiles(model: ModelDescriptor = ModelDescriptors.gemma) {
        val modelFile = fileFor(model)
        if (modelFile.exists() && modelFile.length() == 0L) {
            modelFile.delete()
        }
        val partial = File(modelDirectory, "${model.fileName}.part")
        if (partial.exists()) {
            partial.delete()
        }
    }

    fun clearAll() {
        modelDirectory.listFiles()?.forEach(File::delete)
    }

    fun clear(model: ModelDescriptor) {
        fileFor(model).delete()
        File(modelDirectory, "${model.fileName}.part").delete()
    }

    fun logState(
        tag: String = "ModelStorage",
        model: ModelDescriptor = ModelDescriptors.gemma,
    ) {
        val directory = modelDirectory
        val modelFile = fileFor(model)
        Log.i(
            tag,
            "modelDirectory=${directory.absolutePath} exists=${directory.exists()} canRead=${directory.canRead()} canWrite=${directory.canWrite()}",
        )
        Log.i(
            tag,
            "defaultModelFile=${modelFile.absolutePath} exists=${modelFile.exists()} canRead=${modelFile.canRead()} length=${modelFile.length()}",
        )
        val children = directory.listFiles()
            ?.joinToString(prefix = "[", postfix = "]") { file ->
                "${file.name}(exists=${file.exists()},size=${file.length()})"
            }
            ?: "null"
        Log.i(tag, "modelDirectoryChildren=$children")
    }
}
