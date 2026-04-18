package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors

fun CalorieEstimationModel.requiredModelDescriptors(): List<ModelDescriptor> = when (this) {
    CalorieEstimationModel.Gemma -> listOf(ModelDescriptors.gemma)
}

fun CalorieEstimationModel.primaryModelDescriptor(): ModelDescriptor = requiredModelDescriptors().first()
