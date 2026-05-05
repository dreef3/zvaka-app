package com.dreef3.weightlossapp.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dreef3.weightlossapp.app.AppLaunchActions
import com.dreef3.weightlossapp.app.MainActivity

class HomeStatusWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        HomeStatusWidgetUpdater.requestRefresh(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        HomeStatusWidgetUpdater.requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        HomeStatusWidgetUpdater.requestRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_OPEN_CAMERA -> {
                Log.i(TAG, "Widget requested camera launch")
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = AppLaunchActions.ACTION_OPEN_CAMERA_FROM_WIDGET
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
            }

            ACTION_OPEN_APP -> {
                Log.i(TAG, "Widget requested app launch")
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
            }

            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> HomeStatusWidgetUpdater.requestRefresh(context)
        }
    }

    companion object {
        const val ACTION_OPEN_CAMERA = "com.dreef3.weightlossapp.widget.OPEN_CAMERA"
        const val ACTION_OPEN_APP = "com.dreef3.weightlossapp.widget.OPEN_APP"
        private const val TAG = "HomeStatusWidget"
    }
}
