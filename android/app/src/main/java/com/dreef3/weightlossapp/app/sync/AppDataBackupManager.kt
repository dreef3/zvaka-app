package com.dreef3.weightlossapp.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.data.local.entity.CoachChatMessageEntity
import com.dreef3.weightlossapp.data.local.entity.CoachChatSessionEntity
import com.dreef3.weightlossapp.data.local.entity.DailyCalorieBudgetPeriodEntity
import com.dreef3.weightlossapp.data.local.entity.FoodEntryEntity
import com.dreef3.weightlossapp.data.local.entity.ProfileEntity
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.data.preferences.GemmaBackend
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreSummary(
    val hasProfile: Boolean,
    val hasCompletedOnboarding: Boolean,
)

class AppDataBackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences,
) {
    suspend fun writeBackupArchive(outputStream: OutputStream) {
        val filesDir = context.filesDir
        val photosDirectory = File(filesDir, PHOTOS_DIRECTORY_NAME)
        val payload = createManifestJson(filesDir)

        ZipOutputStream(outputStream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(payload.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            photosDirectory.listFiles()
                ?.filter(File::isFile)
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    zip.putNextEntry(ZipEntry("$PHOTOS_DIRECTORY_NAME/${file.name}"))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    suspend fun restoreBackupArchive(inputStream: InputStream): RestoreSummary {
        val tempDirectory = File(context.cacheDir, "drive-restore").apply {
            deleteRecursively()
            mkdirs()
        }
        val manifestFile = File(tempDirectory, MANIFEST_ENTRY_NAME)
        val extractedPhotosDirectory = File(tempDirectory, PHOTOS_DIRECTORY_NAME).apply { mkdirs() }

        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == MANIFEST_ENTRY_NAME -> {
                        manifestFile.outputStream().use { output -> zip.copyTo(output) }
                    }

                    entry.name.startsWith("$PHOTOS_DIRECTORY_NAME/") && !entry.isDirectory -> {
                        val target = File(extractedPhotosDirectory, entry.name.removePrefix("$PHOTOS_DIRECTORY_NAME/"))
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        require(manifestFile.exists()) { "Backup archive is missing manifest.json." }
        val manifest = JSONObject(manifestFile.readText())
        val photosDirectory = File(context.filesDir, PHOTOS_DIRECTORY_NAME).apply {
            deleteRecursively()
            mkdirs()
        }

        extractedPhotosDirectory.listFiles()
            ?.filter(File::isFile)
            ?.forEach { file ->
                file.copyTo(File(photosDirectory, file.name), overwrite = true)
            }

        database.clearAllTables()
        database.withTransaction {
            restoreManifest(manifest)
        }

        tempDirectory.deleteRecursively()

        val restoredPreferences = manifest.getJSONObject(PREFERENCES_KEY)
        return RestoreSummary(
            hasProfile = !manifest.isNull(PROFILE_KEY),
            hasCompletedOnboarding = restoredPreferences.optBoolean(HAS_COMPLETED_ONBOARDING_KEY, false),
        )
    }

    private suspend fun createManifestJson(filesDir: File): JSONObject {
        val photosRoot = File(filesDir, PHOTOS_DIRECTORY_NAME)
        val profile = database.profileDao().getProfile()
        val budgetPeriods = database.dailyCalorieBudgetPeriodDao().getAll()
        val foodEntries = database.foodEntryDao().getAll()
        val chatSessions = database.coachChatSessionDao().getAll()
        val chatMessages = database.coachChatMessageDao().getAll()
        val preferenceSnapshot = preferences.createUserBackupSnapshot()

        return JSONObject().apply {
            put(SCHEMA_VERSION_KEY, 1)
            put(EXPORTED_AT_EPOCH_MS_KEY, System.currentTimeMillis())
            put(
                PREFERENCES_KEY,
                JSONObject().apply {
                    put(HAS_COMPLETED_ONBOARDING_KEY, preferenceSnapshot.hasCompletedOnboarding)
                    put(COACH_AUTO_ADVICE_ENABLED_KEY, preferenceSnapshot.coachAutoAdviceEnabled)
                    put(COACH_MODEL_KEY, preferenceSnapshot.coachModelStorageKey)
                    put(CALORIE_ESTIMATION_MODEL_KEY, preferenceSnapshot.calorieEstimationModelStorageKey)
                    put(GEMMA_BACKEND_KEY, preferenceSnapshot.gemmaBackendStorageKey)
                    put(TRAINING_DATA_SHARING_ENABLED_KEY, preferenceSnapshot.trainingDataSharingEnabled)
                    put(HEALTH_CONNECT_CALORIES_ENABLED_KEY, preferenceSnapshot.healthConnectCaloriesEnabled)
                },
            )
            put(PROFILE_KEY, profile?.toJson())
            put(BUDGET_PERIODS_KEY, JSONArray().apply { budgetPeriods.forEach { put(it.toJson()) } })
            put(
                FOOD_ENTRIES_KEY,
                JSONArray().apply {
                    foodEntries.forEach { entry ->
                        put(entry.toJson(pathForBackup = { path -> path.toBackupPath(filesDir, photosRoot) }))
                    }
                },
            )
            put(
                COACH_CHAT_SESSIONS_KEY,
                JSONArray().apply { chatSessions.forEach { put(it.toJson()) } },
            )
            put(
                COACH_CHAT_MESSAGES_KEY,
                JSONArray().apply {
                    chatMessages.forEach { message ->
                        put(message.toJson(pathForBackup = { path -> path?.toBackupPath(filesDir, photosRoot) }))
                    }
                },
            )
        }
    }

    private suspend fun restoreManifest(manifest: JSONObject) {
        val preferencesJson = manifest.getJSONObject(PREFERENCES_KEY)
        preferences.restoreUserBackupSnapshot(
            UserPreferenceBackupSnapshot(
                hasCompletedOnboarding = preferencesJson.optBoolean(HAS_COMPLETED_ONBOARDING_KEY, false),
                coachAutoAdviceEnabled = preferencesJson.optBoolean(COACH_AUTO_ADVICE_ENABLED_KEY, true),
                coachModelStorageKey = preferencesJson.optString(
                    COACH_MODEL_KEY,
                    CoachModel.Gemma.storageKey,
                ),
                calorieEstimationModelStorageKey = preferencesJson.optString(
                    CALORIE_ESTIMATION_MODEL_KEY,
                    CalorieEstimationModel.Gemma.storageKey,
                ),
                gemmaBackendStorageKey = preferencesJson.optString(
                    GEMMA_BACKEND_KEY,
                    GemmaBackend.CPU.storageKey,
                ),
                trainingDataSharingEnabled = preferencesJson.optBoolean(TRAINING_DATA_SHARING_ENABLED_KEY, false),
                healthConnectCaloriesEnabled = preferencesJson.optBoolean(HEALTH_CONNECT_CALORIES_ENABLED_KEY, false),
            ),
        )

        manifest.optJSONObject(PROFILE_KEY)?.let { database.profileDao().upsert(it.toProfileEntity()) }

        val budgetPeriods = manifest.getJSONArray(BUDGET_PERIODS_KEY).toBudgetPeriods()
        if (budgetPeriods.isNotEmpty()) {
            database.dailyCalorieBudgetPeriodDao().insertAll(budgetPeriods)
        }

        manifest.getJSONArray(FOOD_ENTRIES_KEY).toFoodEntries(context.filesDir).forEach { entry ->
            if (entry.id == 0L || database.foodEntryDao().update(entry) == 0) {
                database.foodEntryDao().insert(entry)
            }
        }

        manifest.getJSONArray(COACH_CHAT_SESSIONS_KEY).toChatSessions().forEach { session ->
            database.coachChatSessionDao().insert(session)
        }

        manifest.getJSONArray(COACH_CHAT_MESSAGES_KEY).toChatMessages(context.filesDir).forEach { message ->
            database.coachChatMessageDao().insert(message)
        }
    }

    private fun String.toBackupPath(filesDir: File, photosRoot: File): String {
        if (isBlank()) return this
        val file = File(this)
        return when {
            file.startsWith(photosRoot) -> "$PHOTOS_DIRECTORY_NAME/${file.name}"
            file.startsWith(filesDir) -> file.relativeTo(filesDir).invariantSeparatorsPath
            else -> this
        }
    }

    private fun String.toAbsoluteAppPath(filesDir: File): String {
        if (isBlank()) return this
        return if (startsWith("$PHOTOS_DIRECTORY_NAME/")) {
            File(filesDir, this).absolutePath
        } else if (!startsWith("/")) {
            File(filesDir, this).absolutePath
        } else {
            this
        }
    }

    private fun ProfileEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAtEpochMs", createdAtEpochMs)
        put("updatedAtEpochMs", updatedAtEpochMs)
        put("firstName", firstName)
        put("sex", sex)
        put("ageYears", ageYears)
        put("heightCm", heightCm)
        put("weightKg", weightKg)
        put("activityLevel", activityLevel)
    }

    private fun DailyCalorieBudgetPeriodEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("profileId", profileId)
        put("caloriesPerDay", caloriesPerDay)
        put("formulaName", formulaName)
        put("activityMultiplier", activityMultiplier)
        put("effectiveFromDateIso", effectiveFromDateIso)
        put("createdAtEpochMs", createdAtEpochMs)
    }

    private fun FoodEntryEntity.toJson(pathForBackup: (String) -> String): JSONObject = JSONObject().apply {
        put("id", id)
        put("capturedAtEpochMs", capturedAtEpochMs)
        put("entryDateIso", entryDateIso)
        put("imagePath", pathForBackup(imagePath))
        put("estimatedCalories", estimatedCalories)
        put("finalCalories", finalCalories)
        put("confidenceState", confidenceState)
        put("detectedFoodLabel", detectedFoodLabel)
        put("confidenceNotes", confidenceNotes)
        put("confirmationStatus", confirmationStatus)
        put("source", source)
        put("entryStatus", entryStatus)
        put("debugInteractionLog", debugInteractionLog)
        put("deletedAtEpochMs", deletedAtEpochMs)
        put("modelImprovementUploadedAtEpochMs", modelImprovementUploadedAtEpochMs)
    }

    private fun CoachChatSessionEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionDateIso", sessionDateIso)
        put("summary", summary)
        put("createdAtEpochMs", createdAtEpochMs)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    private fun CoachChatMessageEntity.toJson(pathForBackup: (String?) -> String?): JSONObject = JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("role", role)
        put("text", text)
        put("createdAtEpochMs", createdAtEpochMs)
        put("imagePath", pathForBackup(imagePath))
    }

    private fun JSONObject.toProfileEntity(): ProfileEntity = ProfileEntity(
        id = getLong("id"),
        createdAtEpochMs = getLong("createdAtEpochMs"),
        updatedAtEpochMs = getLong("updatedAtEpochMs"),
        firstName = getString("firstName"),
        sex = getString("sex"),
        ageYears = getInt("ageYears"),
        heightCm = getInt("heightCm"),
        weightKg = getDouble("weightKg"),
        activityLevel = getString("activityLevel"),
    )

    private fun JSONArray.toBudgetPeriods(): List<DailyCalorieBudgetPeriodEntity> =
        buildList(length()) {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    DailyCalorieBudgetPeriodEntity(
                        id = item.getLong("id"),
                        profileId = item.getLong("profileId"),
                        caloriesPerDay = item.getInt("caloriesPerDay"),
                        formulaName = item.getString("formulaName"),
                        activityMultiplier = item.getDouble("activityMultiplier"),
                        effectiveFromDateIso = item.getString("effectiveFromDateIso"),
                        createdAtEpochMs = item.getLong("createdAtEpochMs"),
                    ),
                )
            }
        }

    private fun JSONArray.toFoodEntries(filesDir: File): List<FoodEntryEntity> =
        buildList(length()) {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    FoodEntryEntity(
                        id = item.getLong("id"),
                        capturedAtEpochMs = item.getLong("capturedAtEpochMs"),
                        entryDateIso = item.getString("entryDateIso"),
                        imagePath = item.getString("imagePath").toAbsoluteAppPath(filesDir),
                        estimatedCalories = item.getInt("estimatedCalories"),
                        finalCalories = item.getInt("finalCalories"),
                        confidenceState = item.getString("confidenceState"),
                        detectedFoodLabel = item.optString("detectedFoodLabel").takeIf(String::isNotBlank),
                        confidenceNotes = item.optString("confidenceNotes").takeIf(String::isNotBlank),
                        confirmationStatus = item.getString("confirmationStatus"),
                        source = item.getString("source"),
                        entryStatus = item.getString("entryStatus"),
                        debugInteractionLog = item.optString("debugInteractionLog").takeIf(String::isNotBlank),
                        deletedAtEpochMs = item.optLong("deletedAtEpochMs").takeIf { item.has("deletedAtEpochMs") && !item.isNull("deletedAtEpochMs") },
                        modelImprovementUploadedAtEpochMs = item.optLong("modelImprovementUploadedAtEpochMs")
                            .takeIf { item.has("modelImprovementUploadedAtEpochMs") && !item.isNull("modelImprovementUploadedAtEpochMs") },
                    ),
                )
            }
        }

    private fun JSONArray.toChatSessions(): List<CoachChatSessionEntity> =
        buildList(length()) {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CoachChatSessionEntity(
                        id = item.getLong("id"),
                        sessionDateIso = item.getString("sessionDateIso"),
                        summary = item.optString("summary").takeIf(String::isNotBlank),
                        createdAtEpochMs = item.getLong("createdAtEpochMs"),
                        updatedAtEpochMs = item.getLong("updatedAtEpochMs"),
                    ),
                )
            }
        }

    private fun JSONArray.toChatMessages(filesDir: File): List<CoachChatMessageEntity> =
        buildList(length()) {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CoachChatMessageEntity(
                        id = item.getLong("id"),
                        sessionId = item.getLong("sessionId"),
                        role = item.getString("role"),
                        text = item.getString("text"),
                        createdAtEpochMs = item.getLong("createdAtEpochMs"),
                        imagePath = item.optString("imagePath").takeIf(String::isNotBlank)?.toAbsoluteAppPath(filesDir),
                    ),
                )
            }
        }

    private companion object {
        const val MANIFEST_ENTRY_NAME = "manifest.json"
        const val PHOTOS_DIRECTORY_NAME = "photos"
        const val SCHEMA_VERSION_KEY = "schemaVersion"
        const val EXPORTED_AT_EPOCH_MS_KEY = "exportedAtEpochMs"
        const val PREFERENCES_KEY = "preferences"
        const val PROFILE_KEY = "profile"
        const val BUDGET_PERIODS_KEY = "budgetPeriods"
        const val FOOD_ENTRIES_KEY = "foodEntries"
        const val COACH_CHAT_SESSIONS_KEY = "coachChatSessions"
        const val COACH_CHAT_MESSAGES_KEY = "coachChatMessages"
        const val HAS_COMPLETED_ONBOARDING_KEY = "hasCompletedOnboarding"
        const val COACH_AUTO_ADVICE_ENABLED_KEY = "coachAutoAdviceEnabled"
        const val COACH_MODEL_KEY = "coachModel"
        const val CALORIE_ESTIMATION_MODEL_KEY = "calorieEstimationModel"
        const val GEMMA_BACKEND_KEY = "gemmaBackend"
        const val TRAINING_DATA_SHARING_ENABLED_KEY = "trainingDataSharingEnabled"
        const val HEALTH_CONNECT_CALORIES_ENABLED_KEY = "healthConnectCaloriesEnabled"
    }
}
