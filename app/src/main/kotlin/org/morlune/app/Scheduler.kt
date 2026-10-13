// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.morlune.engine.ProviderException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "weather-periodic"
    private const val ONCE = "weather-once"
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** (Re)creates the repeating fetch at the interval chosen in the settings (1h .. once a day). */
    fun schedule(ctx: Context) {
        val minutes = GlobalSettings.of(ctx).refresh.minutes.toLong()
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun fetchNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(needsNetwork).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
    }
}

class WeatherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private fun report(msg: String) {
        Prefs.global(applicationContext).edit().putString("last_status", msg).commit()
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        val g = GlobalSettings.of(ctx)
        val location = g.location() ?: return Result.success()   // nothing to do until a location is set
        report("Fetching from ${g.providerId}…")
        return try {
            val registry = ProviderStore.registry(ctx, ::report)
            report("Providers loaded: ${registry.all().joinToString { it.id }}")
            val data = registry.fetchWithFallback(g.providerId, if (g.fallbackToOpenMeteo) "open-meteo" else null, location)
            WeatherCache.save(ctx, data)
            val note = data.warnings.firstOrNull()?.let { " ($it)" }.orEmpty()
            report("Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())} from ${data.providerId}$note")
            WidgetUpdater.updateAll(ctx)
            Result.success()
        } catch (e: ProviderException) {
            Log.w("Morlune", "fetch failed: ${e.message}")
            report("Failed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.success()   // keep showing the last good data
        } catch (e: Throwable) {
            // Anything unexpected must still leave a visible reason behind.
            Log.e("Morlune", "fetch crashed", e)
            report("Crashed: ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }
}
