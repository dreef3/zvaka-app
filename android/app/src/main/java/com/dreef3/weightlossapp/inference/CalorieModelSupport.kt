package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors

fun CalorieEstimationModel.requiredModelDescriptors(): List<ModelDescriptor> = when (this) {
    CalorieEstimationModel.Gemma -> listOf(ModelDescriptors.gemma)
    CalorieEstimationModel.SmolVlm -> listOf(ModelDescriptors.smolVlm, ModelDescriptors.smolVlmMmproj)
    CalorieEstimationModel.Qwen0_8b -> listOf(ModelDescriptors.qwen0_8b, ModelDescriptors.qwen0_8bMmproj)
    CalorieEstimationModel.Qwen2b -> listOf(ModelDescriptors.qwen2b, ModelDescriptors.qwen2bMmproj)
}

fun CalorieEstimationModel.primaryModelDescriptor(): ModelDescriptor = requiredModelDescriptors().first()