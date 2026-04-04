package com.dreef3.weightlossapp.app.media

data class ModelDownloadState(
    val isDownloading: Boolean = false,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = ModelDownloadConfig.MODEL_TOTAL_BYTES,
    val errorMessage: String? = null,
)
