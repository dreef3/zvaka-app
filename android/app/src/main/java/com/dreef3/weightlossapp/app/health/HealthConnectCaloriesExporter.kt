package com.dreef3.weightlossapp.app.health

import android.content.Context
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
import java.time.ZoneId

class HealthConnectCaloriesExporter(
    private val context: Context,
) {
    private val nutritionWritePermission = HealthPermission.getWritePermission(NutritionRecord::class)

    fun isAvailable(): Boolean =
        HealthConnectClient.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun permissionsLauncherContract() =
        PermissionController.createRequestPermissionResultContract()

    fun requiredPermissions(): Set<String> = setOf(nutritionWritePermission)

    suspend fun hasWritePermission(): Boolean {
        if (!isAvailable()) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().contains(nutritionWritePermission)
    }

    suspend fun upsertCalories(entry: FoodEntry) {
        if (!shouldPublish(entry) || !hasWritePermission()) return
        val client = HealthConnectClient.getOrCreate(context)
        client.insertRecords(
            listOf(
                NutritionRecord(
                    startTime = entry.capturedAt,
                    startZoneOffset = zoneOffsetFor(entry.capturedAt),
                    endTime = entry.capturedAt,
                    endZoneOffset = zoneOffsetFor(entry.capturedAt),
                    energy = Energy.kilocalories(entry.finalCalories.toDouble()),
                    name = entry.detectedFoodLabel,
                    metadata = Metadata(
                        clientRecordId = clientRecordId(entry.id),
                        clientRecordVersion = 0,
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

    private fun clientRecordId(entryId: Long): String = "weightlossapp-food-entry-$entryId"
}
