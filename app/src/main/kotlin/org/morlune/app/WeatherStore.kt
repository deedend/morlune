// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.morlune.engine.HttpFetcher
import org.morlune.engine.HttpResult
import org.morlune.engine.ProviderException
import org.morlune.engine.ProviderLoader
import org.morlune.engine.ProviderRegistry
import org.morlune.engine.UrlConnectionFetcher
import org.morlune.engine.WeatherData
import org.morlune.engine.WeatherJson
import java.io.File

/** Last good weather, kept on disk so widgets can draw without the network. */
object WeatherCache {
    private fun file(ctx: Context) = File(ctx.filesDir, "weather.json")

    fun load(ctx: Context): WeatherData? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.let { WeatherJson.fromJson(it) }
    } catch (e: Exception) {
        Log.w(TAG, "cache unreadable: ${e.message}")
        null
    }

    fun save(ctx: Context, data: WeatherData) {
        val tmp = File(ctx.filesDir, "weather.json.tmp")
        tmp.writeText(WeatherJson.toJson(data))
        tmp.renameTo(file(ctx))
    }

    private const val TAG = "Morlune"
}

private class ReportingFetcher(private val inner: HttpFetcher, private val report: (String) -> Unit) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val host = Uri.parse(url).host ?: url
        report("Requesting $host…")
        try {
            val r = inner.get(url, headers)
            report("$host answered HTTP ${r.status}, ${r.body.length} chars")
            return r
        } catch (e: Throwable) {
            report("$host: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}

/** Built-in providers live in assets/providers, imported ones in filesDir/providers. */
object ProviderStore {
    private const val TAG = "Morlune"
    private const val ASSET_DIR = "providers"

    private fun userDir(ctx: Context) = File(ctx.filesDir, "providers").also { it.mkdirs() }

    /** [report] (optional) is told about each network step, so a stuck fetch can be located from the widget. */
    fun registry(ctx: Context, report: ((String) -> Unit)? = null): ProviderRegistry {
        val plain = UrlConnectionFetcher(userAgent = "Morlune/${BuildConfig.VERSION_NAME} (open source Android widget)")
        val fetcher: HttpFetcher = if (report == null) plain else ReportingFetcher(plain, report)
        val reg = ProviderRegistry(fetcher)
        builtinJson(ctx).forEach { (name, text) -> add(reg, name, text) }
        userDir(ctx).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { add(reg, it.name, it.readText()) }
        return reg
    }

    private fun add(reg: ProviderRegistry, source: String, text: String) {
        try { reg.addFromJson(text) } catch (e: ProviderException) { Log.w(TAG, "provider $source skipped: ${e.message}") }
    }

    private fun builtinJson(ctx: Context): List<Pair<String, String>> =
        ctx.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.sorted()
            .map { it to ctx.assets.open("$ASSET_DIR/$it").bufferedReader().use { r -> r.readText() } }

    fun builtinIds(ctx: Context): Set<String> =
        builtinJson(ctx).mapNotNull { runCatching { ProviderLoader.parse(it.second).id }.getOrNull() }.toSet()

    /** Validates then stores a provider file. Returns the provider id, or throws ProviderException with a readable reason. */
    fun import(ctx: Context, text: String): String {
        val def = ProviderLoader.parse(text)
        if (def.id in builtinIds(ctx)) throw ProviderException("'${def.id}' is a built-in provider; pick another id")
        File(userDir(ctx), "${def.id}.json").writeText(text)
        return def.id
    }

    fun importedIds(ctx: Context): List<String> =
        userDir(ctx).listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    fun remove(ctx: Context, id: String) { File(userDir(ctx), "$id.json").delete() }
}
