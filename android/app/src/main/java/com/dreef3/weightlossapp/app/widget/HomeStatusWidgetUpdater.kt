package com.dreef3.weightlossapp.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dreef3.weightlossapp.R
import com.dreef3.weightlossapp.app.AppLaunchActions
import com.dreef3.weightlossapp.app.MainActivity
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.work.WorkManagerEngineTaskQueue
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object HomeStatusWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestRefresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            updateAll(appContext)
        }
    }

    private suspend fun updateAll(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, HomeStatusWidgetProvider::class.java),
        )
        if (widgetIds.isEmpty()) return

        AppContainer.initialize(context)
        val container = AppContainer.instance
        val today = container.localDateProvider.today()
        val budget = container.profileRepository.findBudgetFor(today)?.caloriesPerDay
        val entries = container.foodEntryRepository.getEntriesInRange(today, today)
        val activeQueueCount = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerEngineTaskQueue.UNIQUE_WORK_NAME)
            .get()
            .count { it.state in ACTIVE_STATES }
        val processingCount = entries.count { it.deletedAt == null && it.entryStatus == FoodEntryStatus.Processing }
        val backgroundCount = maxOf(processingCount, activeQueueCount)

        val widgetState = if (budget == null) {
            WidgetState(
                primaryText = context.getString(R.string.widget_setup_primary),
                secondaryText = context.getString(R.string.widget_setup_secondary),
                backgroundText = null,
            )
        } else {
            val summary = container.summaryAggregator.buildSummary(today, budget, entries)
            val remaining = summary.remainingCalories
            WidgetState(
                primaryText = NumberFormat.getIntegerInstance().format(kotlin.math.abs(remaining)),
                secondaryText = if (remaining >= 0) {
                    context.getString(R.string.widget_remaining_label)
                } else {
                    context.getString(R.string.widget_over_label)
                },
                backgroundText = if (backgroundCount > 0) {
                    countLabel(
                        context = context,
                        count = backgroundCount,
                        singularRes = R.string.widget_background_item_singular,
                        pluralRes = R.string.widget_background_item_plural,
                    )
                } else {
                    null
                },
            )
        }

        widgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(
                appWidgetId,
                buildRemoteViews(context, widgetState),
            )
        }
    }

    private fun buildRemoteViews(
        context: Context,
        state: WidgetState,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_home_status).apply {
        setTextViewText(R.id.widget_primary_text, state.primaryText)
        setTextViewText(R.id.widget_secondary_text, state.secondaryText)
        if (state.backgroundText == null) {
            setViewVisibility(R.id.widget_background_text, View.GONE)
        } else {
            setViewVisibility(R.id.widget_background_text, View.VISIBLE)
            setTextViewText(R.id.widget_background_text, state.backgroundText)
        }
        setOnClickPendingIntent(R.id.widget_camera_button, cameraPendingIntent(context))
        setOnClickPendingIntent(R.id.widget_status_button, openAppPendingIntent(context))
    }

    private fun cameraPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, HomeStatusWidgetProvider::class.java).apply {
            action = HomeStatusWidgetProvider.ACTION_OPEN_CAMERA
        }
        return PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, HomeStatusWidgetProvider::class.java).apply {
            action = HomeStatusWidgetProvider.ACTION_OPEN_APP
        }
        return PendingIntent.getBroadcast(
            context,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun countLabel(
        context: Context,
        count: Int,
        singularRes: Int,
        pluralRes: Int,
    ): String = if (count == 1) {
        context.getString(singularRes, count)
    } else {
        context.getString(pluralRes, count)
    }

    private data class WidgetState(
        val primaryText: String,
        val secondaryText: String,
        val backgroundText: String?,
    )

    private val ACTIVE_STATES = setOf(
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.RUNNING,
        WorkInfo.State.BLOCKED,
    )
}
