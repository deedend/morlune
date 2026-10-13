// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import android.util.Log
import org.morlune.engine.CalEvent

/** Reads upcoming events from the system calendar provider, keeping the colours the calendar app uses. */
object CalendarRepository {
    private const val TAG = "Morlune"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private val projection = arrayOf(
        Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
        Instances.EVENT_COLOR, Instances.CALENDAR_COLOR, Instances.EVENT_LOCATION, Instances.SELF_ATTENDEE_STATUS,
    )

    fun query(ctx: Context, fromMs: Long, toMs: Long): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().appendPath(fromMs.toString()).appendPath(toMs.toString()).build()
        val out = ArrayList<CalEvent>()
        try {
            // Only calendars the user has switched on in the calendar app.
            ctx.contentResolver.query(uri, projection, "${Instances.VISIBLE}=1", null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val color = if (!c.isNull(5) && c.getInt(5) != 0) c.getInt(5) else c.getInt(6)
                    out += CalEvent(
                        id = c.getLong(0),
                        title = c.getString(1).let { if (it.isNullOrBlank()) "(no title)" else it },
                        startMs = c.getLong(2), endMs = c.getLong(3), allDay = c.getInt(4) == 1,
                        color = color or (0xFF shl 24), location = c.getString(7),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "calendar read refused: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "calendar query failed: ${e.message}")
        }
        return out
    }
}
