package com.dreef3.weightlossapp.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import java.time.Instant
import java.time.Duration
import java.time.ZoneId

class HealthConnectCaloriesExporter(
    private val context: Context,
) : HealthConnectCaloriesPublisher {
    private val nutritionWritePermission = HealthPermission.getWritePermission(NutritionRecord::class)

    fun sdkStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HealthConnectClient.getSdkStatus(context)
        } else {
            HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PROVIDER_PACKAGE)
        }

    fun isAvailable(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun needsProviderSetup(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    fun permissionsLauncherContract() =
        PermissionController.createRequestPermissionResultContract()

    fun requiredPermissions(): Set<String> = setOf(nutritionWritePermission)

    fun openProviderSetup() {
        val marketIntent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(PLAY_STORE_PACKAGE)
            data = Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(marketIntent) }
            .recoverCatching { context.startActivity(fallbackIntent) }
            .getOrThrow()
    }

    override suspend fun hasWritePermission(): Boolean {
        if (!isAvailable()) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().contains(nutritionWritePermission)
    }

    override suspend fun upsertCalories(entry: FoodEntry) {
        if (!shouldPublish(entry) || !hasWritePermission()) return
        val client = HealthConnectClient.getOrCreate(context)
        val endTime = nutritionEndTime(entry.capturedAt)
        client.insertRecords(
            listOf(
                NutritionRecord(
                    startTime = entry.capturedAt,
                    startZoneOffset = zoneOffsetFor(entry.capturedAt),
                    endTime = endTime,
                    endZoneOffset = zoneOffsetFor(endTime),
                    energy = Energy.kilocalories(entry.finalCalories.toDouble()),
                    name = entry.detectedFoodLabel,
                    metadata = Metadata.manualEntry(
                        clientRecordId(entry.id),
                        0,
                        device = Device(type = Device.TYPE_PHONE, manufacturer = null, model = null),
                    ),
                ),
            ),
        )
    }

    suspend fun deleteCalories(entryId: Long) {
        if (!hasWritePermission()) return
        val client = HealthConnectClient.getOrCreate(context)
        client.deleteRecords(
            NutritionRecord::class,
            emptyList(),
            listOf(clientRecordId(entryId)),
        )
    }

    private fun shouldPublish(entry: FoodEntry): Boolean =
        entry.id != 0L &&
            entry.deletedAt == null &&
            entry.entryStatus == FoodEntryStatus.Ready &&
            entry.confirmationStatus != ConfirmationStatus.Rejected &&
            entry.finalCalories > 0

    private fun zoneOffsetFor(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    private fun nutritionEndTime(startTime: Instant): Instant = startTime.plus(Duration.ofMinutes(1))

    private fun clientRecordId(entryId: Long): String = "weightlossapp-food-entry-$entryId"

    companion object {
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
