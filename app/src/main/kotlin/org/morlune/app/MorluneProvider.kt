// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class MorluneProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, WidgetRenderer.render(context, it)) }
        Scheduler.schedule(context)   // keeps the WorkManager job in step with the settings
    }

    override fun onEnabled(context: Context) {
        Scheduler.schedule(context)
        Scheduler.fetchNow(context)
    }

    override fun onDisabled(context: Context) = Scheduler.cancel(context)

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { context.deleteSharedPreferences(Prefs.widgetName(it)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> WidgetUpdater.updateAll(context)
        }
    }
}

object WidgetUpdater {
    private const val TAG = "Morlune"

    fun updateAll(ctx: Context) {
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, MorluneProvider::class.java))
        ids.forEach { update(ctx, it) }
    }

    fun update(ctx: Context, widgetId: Int) {
        try {
            AppWidgetManager.getInstance(ctx).updateAppWidget(widgetId, WidgetRenderer.render(ctx, widgetId))
        } catch (e: RuntimeException) {
            Log.w(TAG, "widget $widgetId not updated: ${e.message}")
        }
    }
}
