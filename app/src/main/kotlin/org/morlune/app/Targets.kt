// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract

/** What each tap area of the widget opens. */
object Targets {
    private fun activity(ctx: Context, request: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun launcher(ctx: Context, pkg: String): Intent? =
        if (pkg.isBlank()) null else ctx.packageManager.getLaunchIntentForPackage(pkg)

    fun settings(ctx: Context, widgetId: Int): Intent =
        Intent(ctx, SettingsActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun clock(ctx: Context, widgetId: Int, pkg: String): PendingIntent {
        val intent = launcher(ctx, pkg) ?: Intent(AlarmClock.ACTION_SHOW_ALARMS)
        return activity(ctx, widgetId * 10 + 1, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun calendar(ctx: Context, widgetId: Int, pkg: String, nowMs: Long): PendingIntent {
        if (!CalendarRepository.hasPermission(ctx)) return activity(ctx, widgetId * 10 + 2, settings(ctx, widgetId))
        val fallback = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(nowMs.toString()).build())
        return activity(ctx, widgetId * 10 + 2, (launcher(ctx, pkg) ?: fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "bom.gov.au" typed without a scheme becomes https://bom.gov.au; blank or non-web values give null. */
    fun normalizeUrl(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val full = if (t.contains("://")) t else "https://$t"
        return full.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** An app chosen in the settings wins (for example Weawow); if it is missing or none is set, the web page opens. */
    fun weather(ctx: Context, widgetId: Int, pkg: String, url: String?, slot: Int = 3): PendingIntent {
        val web = normalizeUrl(url)
        val intent = launcher(ctx, pkg)
            ?: if (web != null) Intent(Intent.ACTION_VIEW, Uri.parse(web)) else settings(ctx, widgetId)
        return activity(ctx, widgetId * 10 + slot, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
