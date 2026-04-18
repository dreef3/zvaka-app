package com.dreef3.weightlossapp.app.sync

data class DriveSyncState(
    val isEnabled: Boolean = false,
    val accountEmail: String? = null,
    val backupFileId: String? = null,
    val lastSyncedAtEpochMs: Long? = null,
    val lastError: String? = null,
)

data class UserPreferenceBackupSnapshot(
    val hasCompletedOnboarding: Boolean,
    val coachAutoAdviceEnabled: Boolean,
    val coachModelStorageKey: String,
    val calorieEstimationModelStorageKey: String,
    val gemmaBackendStorageKey: String,
    val trainingDataSharingEnabled: Boolean,
    val healthConnectCaloriesEnabled: Boolean,
)
