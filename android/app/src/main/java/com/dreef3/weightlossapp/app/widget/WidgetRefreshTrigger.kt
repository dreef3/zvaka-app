package com.dreef3.weightlossapp.app.widget

import android.content.Context

interface WidgetRefreshTrigger {
    fun requestRefresh(reason: String)
}

object NoOpWidgetRefreshTrigger : WidgetRefreshTrigger {
    override fun requestRefresh(reason: String) = Unit
}

class HomeStatusWidgetRefreshTrigger(
    private val context: Context,
) : WidgetRefreshTrigger {
    override fun requestRefresh(reason: String) {
        HomeStatusWidgetUpdater.requestRefresh(context)
    }
}
