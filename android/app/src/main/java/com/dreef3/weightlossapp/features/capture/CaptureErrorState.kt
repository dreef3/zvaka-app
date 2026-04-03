package com.dreef3.weightlossapp.features.capture

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun CaptureErrorState(message: String?) {
    if (message != null) {
        Text(message)
    }
}
