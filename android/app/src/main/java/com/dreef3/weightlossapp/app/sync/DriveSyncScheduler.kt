package com.dreef3.weightlossapp.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dreef3.weightlossapp.work.GoogleDriveSyncWorker
import java.util.concurrent.TimeUnit

class DriveSyncScheduler(
    context: Context,
) : DriveSyncTrigger {
    private val workManager = WorkManager.getInstance(context)

    override fun requestSync(reason: String) {
        val request = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
            .setConstraints(networkConstraints())
            .addTag(reason)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun enablePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<GoogleDriveSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disablePeriodicSync() {
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

    private companion object {
        const val IMMEDIATE_WORK_NAME = "google-drive-sync-immediate"
        const val PERIODIC_WORK_NAME = "google-drive-sync-periodic"
    }
}
