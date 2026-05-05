package com.dreef3.weightlossapp.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.dreef3.weightlossapp.R
import com.dreef3.weightlossapp.app.MainActivity
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
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
        val backgroundCount = entries.count { it.deletedAt == null && it.entryStatus == FoodEntryStatus.Processing }

        val widgetState = if (budget == null) {
            WidgetState(
                primaryText = context.getString(R.string.widget_setup_primary),
                secondaryText = context.getString(R.string.widget_setup_secondary),
                hasBackgroundWork = backgroundCount > 0,
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
                hasBackgroundWork = backgroundCount > 0,
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
        setViewVisibility(
            R.id.widget_processing_indicator,
            if (state.hasBackgroundWork) View.VISIBLE else View.GONE,
        )
        setOnClickPendingIntent(R.id.widget_camera_button, cameraPendingIntent(context))
        setOnClickPendingIntent(R.id.widget_status_button, openAppPendingIntent(context))
    }

    private fun cameraPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetCameraActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class WidgetState(
        val primaryText: String,
        val secondaryText: String,
        val hasBackgroundWork: Boolean,
    )

}
