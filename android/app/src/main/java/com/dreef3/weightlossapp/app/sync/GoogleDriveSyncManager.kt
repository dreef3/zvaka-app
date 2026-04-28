package com.dreef3.weightlossapp.app.sync

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.core.net.toUri
import com.dreef3.weightlossapp.app.network.NetworkConnectionMonitor
import com.dreef3.weightlossapp.app.network.NetworkConnectionType
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveAuthorizationOutcome {
    data class Authorized(
        val accessToken: String,
        val accountEmail: String?,
    ) : DriveAuthorizationOutcome

    data class NeedsResolution(
        val intentSender: IntentSender,
    ) : DriveAuthorizationOutcome
}

data class DriveBackupMetadata(
    val fileId: String,
)

class GoogleDriveSyncManager(
    private val context: Context,
    private val preferences: com.dreef3.weightlossapp.data.preferences.AppPreferences,
    private val backupManager: AppDataBackupManager,
    private val networkConnectionMonitor: NetworkConnectionMonitor,
) {
    suspend fun authorizeInteractively(activity: Activity): DriveAuthorizationOutcome {
        val client = Identity.getAuthorizationClient(activity)
        val savedEmail = preferences.readDriveSyncState().accountEmail
        val result = client.authorize(buildAuthorizationRequest(savedEmail)).await()
        return result.toOutcome(fallbackEmail = savedEmail)
    }

    suspend fun completeAuthorization(
        activity: Activity,
        data: Intent?,
    ): DriveAuthorizationOutcome {
        val client = Identity.getAuthorizationClient(activity)
        val fallbackEmail = preferences.readDriveSyncState().accountEmail
        if (data == null) {
            error("Google Drive authorization was cancelled.")
        }
        return try {
            val result = client.getAuthorizationResultFromIntent(data)
            result.toOutcome(fallbackEmail = fallbackEmail)
        } catch (error: ApiException) {
            val statusLabel = CommonStatusCodes.getStatusCodeString(error.statusCode)
            val detail = error.localizedMessage?.takeIf { it.isNotBlank() }
            error(
                buildString {
                    append("Google Drive authorization failed")
                    if (!statusLabel.isNullOrBlank()) {
                        append(" (").append(statusLabel).append(")")
                    }
                    detail?.let { append(": ").append(it) }
                },
            )
        }
    }

    suspend fun uploadBackup(accessToken: String, accountEmail: String?): DriveBackupMetadata = withContext(Dispatchers.IO) {
        requireWifiForBackupUpload()
        val backupFile = File.createTempFile("drive-backup-", ".zip", context.cacheDir)
        try {
            backupFile.outputStream().use { output ->
                backupManager.writeBackupArchive(output)
            }

            val existingFileId = preferences.readDriveSyncState().backupFileId ?: findExistingBackupFileId(accessToken)
            val fileId = uploadMultipart(
                accessToken = accessToken,
                backupFile = backupFile,
                existingFileId = existingFileId,
            )

            preferences.recordDriveSyncSuccess(
                syncedAtEpochMs = System.currentTimeMillis(),
                backupFileId = fileId,
                accountEmail = accountEmail,
            )
            DriveBackupMetadata(fileId = fileId)
        } finally {
            backupFile.delete()
        }
    }

    suspend fun restoreBackup(accessToken: String): RestoreSummary = withContext(Dispatchers.IO) {
        val fileId = preferences.readDriveSyncState().backupFileId ?: findExistingBackupFileId(accessToken)
            ?: error("No Google Drive backup found for this app.")
        val response = openConnection(
            url = "$DRIVE_FILES_ENDPOINT/$fileId?alt=media",
            accessToken = accessToken,
            method = "GET",
        )
        response.inputStream.use { input -> backupManager.restoreBackupArchive(input) }
    }

    suspend fun tryUploadFromWorker(): Result<DriveBackupMetadata> = runCatching {
        requireWifiForBackupUpload()
        val state = preferences.readDriveSyncState()
        if (!state.isEnabled) error("Google Drive sync is disabled.")
        when (val auth = authorizeSilently()) {
            is DriveAuthorizationOutcome.Authorized -> uploadBackup(auth.accessToken, auth.accountEmail ?: state.accountEmail)
            is DriveAuthorizationOutcome.NeedsResolution -> {
                preferences.recordDriveSyncError("Reconnect Google Drive from Profile to resume automatic sync.")
                error("Drive authorization requires user interaction.")
            }
        }
    }.onFailure {
        preferences.recordDriveSyncError(it.message ?: "Google Drive sync failed.")
    }

    suspend fun disconnect() {
        preferences.clearDriveSyncState()
    }

    private suspend fun authorizeSilently(): DriveAuthorizationOutcome {
        val client = Identity.getAuthorizationClient(context)
        val savedEmail = preferences.readDriveSyncState().accountEmail
        val result = client.authorize(buildAuthorizationRequest(savedEmail)).await()
        return result.toOutcome(fallbackEmail = savedEmail)
    }

    private fun buildAuthorizationRequest(savedEmail: String?): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(DRIVE_APPDATA_SCOPE),
                    Scope(EMAIL_SCOPE),
                    Scope(PROFILE_SCOPE),
                    Scope(OPENID_SCOPE),
                ),
            )
            .apply {
                if (!savedEmail.isNullOrBlank()) {
                    setAccount(Account(savedEmail, GOOGLE_ACCOUNT_TYPE))
                }
            }
            .build()
    }

    private suspend fun findExistingBackupFileId(accessToken: String): String? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("name = '$BACKUP_FILE_NAME' and trashed = false", "UTF-8")
        val fields = URLEncoder.encode("files(id,name)", "UTF-8")
        val connection = openConnection(
            url = "$DRIVE_FILES_ENDPOINT?spaces=appDataFolder&q=$query&fields=$fields",
            accessToken = accessToken,
            method = "GET",
        )
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
        files.optJSONObject(0)?.optString("id")?.takeIf(String::isNotBlank)
    }

    private suspend fun uploadMultipart(
        accessToken: String,
        backupFile: File,
        existingFileId: String?,
    ): String = withContext(Dispatchers.IO) {
        val boundary = "drive-sync-${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            put("mimeType", ZIP_MIME_TYPE)
            if (existingFileId == null) {
                put("parents", JSONArray().put("appDataFolder"))
            }
        }
        val bodyPrefix = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--").append(boundary).append("\r\n")
            append("Content-Type: ").append(ZIP_MIME_TYPE).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val bodySuffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val endpoint = if (existingFileId == null) {
            "$DRIVE_UPLOAD_ENDPOINT?uploadType=multipart"
        } else {
            "$DRIVE_UPLOAD_ENDPOINT/$existingFileId?uploadType=multipart"
        }
        val method = if (existingFileId == null) "POST" else "PATCH"
        val contentLength = bodyPrefix.size.toLong() + backupFile.length() + bodySuffix.size.toLong()
        val connection = prepareConnection(
            url = endpoint,
            accessToken = accessToken,
            method = method,
            contentType = "multipart/related; boundary=$boundary",
        ).apply {
            doOutput = true
            setFixedLengthStreamingMode(contentLength)
            outputStream.use { output ->
                output.write(bodyPrefix)
                backupFile.inputStream().use { input -> input.copyTo(output) }
                output.write(bodySuffix)
            }
        }
        validateResponse(connection)
        val response = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(response).getString("id")
    }

    private fun openConnection(
        url: String,
        accessToken: String,
        method: String,
        contentType: String? = null,
    ): HttpURLConnection {
        val connection = prepareConnection(
            url = url,
            accessToken = accessToken,
            method = method,
            contentType = contentType,
        )
        validateResponse(connection)
        return connection
    }

    private fun prepareConnection(
        url: String,
        accessToken: String,
        method: String,
        contentType: String? = null,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (contentType != null) {
                setRequestProperty("Content-Type", contentType)
            }
            doInput = true
        }

    private fun validateResponse(connection: HttpURLConnection) {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            throw IllegalStateException("Drive API error $responseCode${errorBody?.let { ": $it" } ?: ""}")
        }
    }

    private fun AuthorizationResult.toOutcome(fallbackEmail: String?): DriveAuthorizationOutcome {
        if (hasResolution()) {
            return DriveAuthorizationOutcome.NeedsResolution(requireNotNull(pendingIntent).intentSender)
        }
        val email = toGoogleSignInAccount()?.email ?: fallbackEmail
        val token = accessToken ?: error("Drive authorization did not return an access token.")
        return DriveAuthorizationOutcome.Authorized(
            accessToken = token,
            accountEmail = email,
        )
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { error -> continuation.resumeWithException(error) }
            addOnCanceledListener { continuation.cancel() }
        }

    private fun requireWifiForBackupUpload() {
        val connectionType = networkConnectionMonitor.currentConnectionType()
        check(connectionType == NetworkConnectionType.Wifi) {
            when (connectionType) {
                NetworkConnectionType.Cellular -> "Google Drive backup is Wi-Fi only and will not run on cellular."
                NetworkConnectionType.Offline,
                NetworkConnectionType.Other,
                -> "Google Drive backup waits for a Wi-Fi connection."
                NetworkConnectionType.Wifi -> ""
            }
        }
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val BACKUP_FILE_NAME = "weight-loss-app-backup.zip"
        const val ZIP_MIME_TYPE = "application/zip"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val EMAIL_SCOPE = "email"
        const val PROFILE_SCOPE = "profile"
        const val OPENID_SCOPE = "openid"
        const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
    }
}
