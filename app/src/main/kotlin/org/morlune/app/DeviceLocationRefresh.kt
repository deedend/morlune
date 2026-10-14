// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import java.util.Locale

/** Best-effort location check during a scheduled weather refresh.
 *
 * Uses an existing recent system fix, never starts a new GPS request. Background
 * location may be unavailable depending on the Android version/permission state.
 * In that case, the last saved coordinates are retained.
 */
internal object DeviceLocationRefresh {
    private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L
    private const val MIN_MOVEMENT_METRES = 1000f

    fun updateSavedLocation(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val now = System.currentTimeMillis()
        val latest = try {
            manager.getProviders(true).mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }.filter { fix ->
                fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
                    fix.time in (now - MAX_AGE_MS)..(now + 60_000L) &&
                    (!fix.hasAccuracy() || fix.accuracy <= 2000f)
            }.maxByOrNull { it.time }
        } catch (_: SecurityException) { null } ?: return

        val prefs = Prefs.global(ctx)
        val oldLat = prefs.getString("loc_lat", "")?.replace(',', '.')?.toDoubleOrNull()
        val oldLon = prefs.getString("loc_lon", "")?.replace(',', '.')?.toDoubleOrNull()
        if (oldLat != null && oldLon != null && oldLat in -90.0..90.0 && oldLon in -180.0..180.0) {
            val distance = FloatArray(1)
            android.location.Location.distanceBetween(oldLat, oldLon, latest.latitude, latest.longitude, distance)
            if (distance[0] < MIN_MOVEMENT_METRES) return
        }

        // Only reverse-geocode when the coordinates actually change. Keep a user's
        // explicit place-name override untouched; the suburb is merely its fallback.
        val suburb = if (Geocoder.isPresent()) try {
            @Suppress("DEPRECATION")
            val address = Geocoder(ctx, Locale.getDefault())
                .getFromLocation(latest.latitude, latest.longitude, 1)?.firstOrNull()
            address?.subLocality?.takeIf { it.isNotBlank() }
                ?: address?.locality?.takeIf { it.isNotBlank() }
                ?: address?.adminArea?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("Morlune", "Could not resolve location name", e)
            null
        } else null

        val lat = String.format(Locale.ROOT, "%.4f", latest.latitude)
        val lon = String.format(Locale.ROOT, "%.4f", latest.longitude)
        prefs.edit()
            .putString("loc_lat", lat)
            .putString("loc_lon", lon)
            .putString("loc_suburb", suburb.orEmpty())
            .putString("loc_suburb_coordinates", "${latest.latitude.to4Decimals()},${latest.longitude.to4Decimals()}")
            .apply()
    }

    private fun Double.to4Decimals(): Double = String.format(Locale.ROOT, "%.4f", this).toDouble()
}
