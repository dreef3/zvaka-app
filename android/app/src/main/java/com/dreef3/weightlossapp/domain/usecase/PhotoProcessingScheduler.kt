package com.dreef3.weightlossapp.domain.usecase

interface PhotoProcessingScheduler {
    fun enqueue(
        entryId: Long,
        imagePath: String,
        capturedAtEpochMs: Long,
    )
}
