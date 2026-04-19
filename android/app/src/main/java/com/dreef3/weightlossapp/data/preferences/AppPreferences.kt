package com.dreef3.weightlossapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dreef3.weightlossapp.app.sync.DriveSyncState
import com.dreef3.weightlossapp.app.sync.DriveSyncTrigger
import com.dreef3.weightlossapp.app.sync.NoOpDriveSyncTrigger
import com.dreef3.weightlossapp.app.sync.UserPreferenceBackupSnapshot
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppPreferences(
    private val context: Context,
    private val driveSyncTrigger: DriveSyncTrigger = NoOpDriveSyncTrigger,
    dataStoreName: String = DEFAULT_DATA_STORE_NAME,
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(dataStoreName) },
    )

    val hasCompletedOnboarding: Flow<Boolean> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.HasCompletedOnboarding] ?: false }

    val coachAutoAdviceEnabled: Flow<Boolean> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.CoachAutoAdviceEnabled] ?: true }

    val coachModel: Flow<CoachModel> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            prefs[Keys.CoachModel]?.let(CoachModel::fromStorageKey)
                ?: CoachModel.Gemma
        }

    val gemmaBackend: Flow<GemmaBackend> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            prefs[Keys.GemmaBackend]?.let(GemmaBackend::fromStorageKey)
                ?: GemmaBackend.CPU
        }

    val calorieEstimationModel: Flow<CalorieEstimationModel> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            prefs[Keys.CalorieEstimationModel]?.let(CalorieEstimationModel::fromStorageKey)
                ?: CalorieEstimationModel.Gemma
        }

    val trainingDataSharingEnabled: Flow<Boolean> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.TrainingDataSharingEnabled] ?: false }

    val healthConnectCaloriesEnabled: Flow<Boolean> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.HealthConnectCaloriesEnabled] ?: false }
    val driveSyncState: Flow<DriveSyncState> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            DriveSyncState(
                isEnabled = prefs[Keys.GoogleDriveSyncEnabled] ?: false,
                accountEmail = prefs[Keys.GoogleDriveAccountEmail],
                backupFileId = prefs[Keys.GoogleDriveBackupFileId],
                lastSyncedAtEpochMs = prefs[Keys.GoogleDriveLastSyncedAtEpochMs],
                lastError = prefs[Keys.GoogleDriveLastError],
            )
        }

    val hasCompletedPhotoNormalizationMigration: Flow<Boolean> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.HasCompletedPhotoNormalizationMigration] ?: false }

    suspend fun setCompletedOnboarding(value: Boolean) {
        if (hasCompletedOnboarding.first() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.HasCompletedOnboarding] = value
        }
        driveSyncTrigger.requestSync("preferences:onboarding")
    }

    suspend fun setCoachAutoAdviceEnabled(value: Boolean) {
        if (coachAutoAdviceEnabled.first() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.CoachAutoAdviceEnabled] = value
        }
        driveSyncTrigger.requestSync("preferences:coach_auto_advice")
    }

    suspend fun setCoachModel(value: CoachModel) {
        if (readCoachModel() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.CoachModel] = value.storageKey
        }
        driveSyncTrigger.requestSync("preferences:coach_model")
    }

    suspend fun setGemmaBackend(value: GemmaBackend) {
        if (readGemmaBackend() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.GemmaBackend] = value.storageKey
        }
        driveSyncTrigger.requestSync("preferences:gemma_backend")
    }

    suspend fun setCalorieEstimationModel(value: CalorieEstimationModel) {
        if (readCalorieEstimationModel() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.CalorieEstimationModel] = value.storageKey
        }
        driveSyncTrigger.requestSync("preferences:calorie_model")
    }

    suspend fun setTrainingDataSharingEnabled(value: Boolean) {
        if (trainingDataSharingEnabled.first() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.TrainingDataSharingEnabled] = value
        }
        driveSyncTrigger.requestSync("preferences:training_data_sharing")
    }

    suspend fun setHealthConnectCaloriesEnabled(value: Boolean) {
        if (healthConnectCaloriesEnabled.first() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.HealthConnectCaloriesEnabled] = value
        }
        driveSyncTrigger.requestSync("preferences:health_connect_calories")
    }

    suspend fun setPhotoNormalizationMigrationCompleted(value: Boolean) {
        if (hasCompletedPhotoNormalizationMigration.first() == value) return
        dataStore.edit { prefs ->
            prefs[Keys.HasCompletedPhotoNormalizationMigration] = value
        }
    }
    suspend fun readCalorieEstimationModel(): CalorieEstimationModel =
        calorieEstimationModel.map { it }.catch {
            if (it is IOException) emit(CalorieEstimationModel.Gemma) else throw it
        }.first()

    suspend fun readCoachModel(): CoachModel =
        coachModel.map { it }.catch {
            if (it is IOException) emit(CoachModel.Gemma) else throw it
        }.first()

    suspend fun readGemmaBackend(): GemmaBackend =
        gemmaBackend.map { it }.catch {
            if (it is IOException) emit(GemmaBackend.CPU) else throw it
        }.first()

    suspend fun readDriveSyncState(): DriveSyncState = driveSyncState.first()

    suspend fun setDriveSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.GoogleDriveSyncEnabled] = enabled
            if (!enabled) {
                prefs.remove(Keys.GoogleDriveLastError)
            }
        }
    }

    suspend fun setDriveSyncAccountEmail(email: String?) {
        dataStore.edit { prefs ->
            if (email.isNullOrBlank()) {
                prefs.remove(Keys.GoogleDriveAccountEmail)
            } else {
                prefs[Keys.GoogleDriveAccountEmail] = email
            }
        }
    }

    suspend fun setDriveBackupFileId(fileId: String?) {
        dataStore.edit { prefs ->
            if (fileId.isNullOrBlank()) {
                prefs.remove(Keys.GoogleDriveBackupFileId)
            } else {
                prefs[Keys.GoogleDriveBackupFileId] = fileId
            }
        }
    }

    suspend fun recordDriveSyncSuccess(
        syncedAtEpochMs: Long,
        backupFileId: String?,
        accountEmail: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.GoogleDriveSyncEnabled] = true
            prefs[Keys.GoogleDriveLastSyncedAtEpochMs] = syncedAtEpochMs
            prefs.remove(Keys.GoogleDriveLastError)
            if (backupFileId.isNullOrBlank()) {
                prefs.remove(Keys.GoogleDriveBackupFileId)
            } else {
                prefs[Keys.GoogleDriveBackupFileId] = backupFileId
            }
            if (accountEmail.isNullOrBlank()) {
                prefs.remove(Keys.GoogleDriveAccountEmail)
            } else {
                prefs[Keys.GoogleDriveAccountEmail] = accountEmail
            }
        }
    }

    suspend fun recordDriveSyncError(message: String) {
        dataStore.edit { prefs ->
            prefs[Keys.GoogleDriveLastError] = message
        }
    }

    suspend fun clearDriveSyncState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.GoogleDriveSyncEnabled)
            prefs.remove(Keys.GoogleDriveAccountEmail)
            prefs.remove(Keys.GoogleDriveBackupFileId)
            prefs.remove(Keys.GoogleDriveLastSyncedAtEpochMs)
            prefs.remove(Keys.GoogleDriveLastError)
        }
    }

    suspend fun createUserBackupSnapshot(): UserPreferenceBackupSnapshot =
        UserPreferenceBackupSnapshot(
            hasCompletedOnboarding = hasCompletedOnboarding.first(),
            coachAutoAdviceEnabled = coachAutoAdviceEnabled.first(),
            coachModelStorageKey = readCoachModel().storageKey,
            calorieEstimationModelStorageKey = readCalorieEstimationModel().storageKey,
            gemmaBackendStorageKey = readGemmaBackend().storageKey,
            trainingDataSharingEnabled = trainingDataSharingEnabled.first(),
            healthConnectCaloriesEnabled = healthConnectCaloriesEnabled.first(),
        )

    suspend fun restoreUserBackupSnapshot(snapshot: UserPreferenceBackupSnapshot) {
        dataStore.edit { prefs ->
            prefs[Keys.HasCompletedOnboarding] = snapshot.hasCompletedOnboarding
            prefs[Keys.CoachAutoAdviceEnabled] = snapshot.coachAutoAdviceEnabled
            prefs[Keys.CoachModel] = snapshot.coachModelStorageKey
            prefs[Keys.CalorieEstimationModel] = snapshot.calorieEstimationModelStorageKey
            prefs[Keys.GemmaBackend] = snapshot.gemmaBackendStorageKey
            prefs[Keys.TrainingDataSharingEnabled] = snapshot.trainingDataSharingEnabled
            prefs[Keys.HealthConnectCaloriesEnabled] = snapshot.healthConnectCaloriesEnabled
        }
    }

    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private object Keys {
        val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
        val CoachAutoAdviceEnabled = booleanPreferencesKey("coach_auto_advice_enabled")
        val CoachModel = stringPreferencesKey("coach_model")
        val GemmaBackend = stringPreferencesKey("gemma_backend")
        val CalorieEstimationModel = stringPreferencesKey("calorie_estimation_model")
        val TrainingDataSharingEnabled = booleanPreferencesKey("training_data_sharing_enabled")
        val HealthConnectCaloriesEnabled = booleanPreferencesKey("health_connect_calories_enabled")
        val HasCompletedPhotoNormalizationMigration = booleanPreferencesKey("photo_normalization_migration_completed")
        val GoogleDriveSyncEnabled = booleanPreferencesKey("google_drive_sync_enabled")
        val GoogleDriveAccountEmail = stringPreferencesKey("google_drive_account_email")
        val GoogleDriveBackupFileId = stringPreferencesKey("google_drive_backup_file_id")
        val GoogleDriveLastSyncedAtEpochMs = longPreferencesKey("google_drive_last_synced_at_epoch_ms")
        val GoogleDriveLastError = stringPreferencesKey("google_drive_last_error")
    }

    companion object {
        private const val DEFAULT_DATA_STORE_NAME = "app_prefs"
    }
}
