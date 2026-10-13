// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.morlune.engine.ProviderException
import java.util.Locale

/**
 * One activity, two jobs:
 *  - started by the launcher when a widget is placed or reconfigured (carries EXTRA_APPWIDGET_ID): per-widget settings
 *  - started from the app icon (or from the widget settings): global weather source / location / update settings
 */
class SettingsActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val askCalendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetUpdater.update(this, widgetId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()   // Android 15 forces this anyway; doing it explicitly lets us pad for the system bars
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        val container = findViewById<View>(R.id.settings_container)
        val barHeight = (56 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.setPadding(bars.left, bars.top, bars.right, 0)
            toolbar.layoutParams = toolbar.layoutParams.apply { height = barHeight + bars.top }
            container.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val globalOnly = intent.getBooleanExtra(EXTRA_GLOBAL, false) || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID

        if (!globalOnly) {
            // Accept the widget straight away; the settings can be changed afterwards.
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            WidgetUpdater.update(this, widgetId)
            if (!CalendarRepository.hasPermission(this)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        if (savedInstanceState == null) {
            val fragment = if (globalOnly) GlobalSettingsFragment() else WidgetSettingsFragment().apply {
                arguments = Bundle().apply { putInt(ARG_WIDGET_ID, widgetId) }
            }
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commit()
        }
    }

    override fun onPause() {
        super.onPause()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetUpdater.update(this, widgetId) else WidgetUpdater.updateAll(this)
    }

    companion object {
        const val EXTRA_GLOBAL = "org.morlune.app.GLOBAL"
        const val ARG_WIDGET_ID = "widget_id"
    }
}

/** Launcher apps for the "which app opens" choices. */
private fun launcherApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase(Locale.getDefault()) }
}

class WidgetSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val id = requireArguments().getInt(SettingsActivity.ARG_WIDGET_ID)
        preferenceManager.sharedPreferencesName = Prefs.widgetName(id)
        setPreferencesFromResource(R.xml.prefs_widget, rootKey)

        val apps = launcherApps(requireContext())
        for (key in listOf("clock_app", "calendar_app", "weather_app", "forecast_app")) {
            findPreference<ListPreference>(key)?.apply {
                val first = getString(if (key == "forecast_app") R.string.same_as_weather else R.string.default_app)
                entries = (listOf(first) + apps.map { it.first }).toTypedArray()
                entryValues = (listOf("") + apps.map { it.second }).toTypedArray()
            }
        }
        findPreference<androidx.preference.EditTextPreference>("weather_url")?.setOnBindEditTextListener {
            it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        findPreference<Preference>("open_global")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_GLOBAL, true))
            true
        }
    }
}

class GlobalSettingsFragment : PreferenceFragmentCompat() {
    /** Keys whose change means the weather has to be fetched again (or rescheduled). */
    private val weatherKeys = setOf("provider_id", "fallback_open_meteo", "extra_values", "loc_lat", "loc_lon", "loc_zone", "loc_name", "refresh_minutes", "apple_team_id", "apple_service_id", "apple_key_id")
    private var dirty = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key in weatherKeys) dirty = true
        if (key == "last_status") activity?.runOnUiThread {
            findPreference<Preference>("refresh_now")?.summary = prefs.getString("last_status", null)
            showCredit()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importProvider(uri) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) importPictures(uri) }
    private val pickAppleKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importAppleKey(uri) }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fillFromDevice() else toast(getString(R.string.location_permission_denied))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.GLOBAL
        setPreferencesFromResource(R.xml.prefs_global, rootKey)
        fillProviderList()

        findPreference<Preference>("import_provider")?.setOnPreferenceClickListener { pickFile.launch(arrayOf("application/json", "text/*", "*/*")); true }
        findPreference<Preference>("remove_provider")?.setOnPreferenceClickListener { removeProviderDialog(); true }
        findPreference<Preference>("apple_import_key")?.apply {
            summary = getString(if (AppleTokens.hasKey(requireContext())) R.string.apple_key_loaded else R.string.apple_key_missing)
            setOnPreferenceClickListener { pickAppleKey.launch(arrayOf("*/*")); true }
        }
        showCredit()
        findPreference<Preference>("data_credit")?.setOnPreferenceClickListener {
            val url = WeatherCache.load(requireContext())?.let { d -> firstUrl(d.attribution) ?: d.webUrl }
            if (url != null && url.startsWith("http")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }
        findPreference<Preference>("pictures_choose")?.setOnPreferenceClickListener { pickFolder.launch(null); true }
        findPreference<Preference>("pictures_clear")?.apply {
            summary = getString(R.string.pictures_count, Pictures.count(requireContext()))
            setOnPreferenceClickListener {
                Pictures.clear(requireContext())
                summary = getString(R.string.pictures_count, 0)
                WidgetUpdater.updateAll(requireContext())
                toast(getString(R.string.pictures_removed))
                true
            }
        }
        findPreference<Preference>("use_device_location")?.setOnPreferenceClickListener {
            if (requireContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) fillFromDevice()
            else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            true
        }
        findPreference<Preference>("refresh_now")?.apply {
            summary = Prefs.global(requireContext()).getString("last_status", null)
            setOnPreferenceClickListener {
                Scheduler.fetchNow(requireContext())
                toast(getString(R.string.refreshing))
                true
            }
        }
        // Numbers only (comma or dot) for coordinates.
        for (key in listOf("loc_lat", "loc_lon")) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }
        findPreference<EditTextPreference>("extra_values")?.setOnBindEditTextListener { it.minLines = 3 }
    }

    override fun onStart() {
        super.onStart()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStop() {
        super.onStop()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        if (dirty) {
            dirty = false
            Scheduler.schedule(requireContext())
            Scheduler.fetchNow(requireContext())
        }
    }

    private fun fillProviderList() {
        val providers = ProviderStore.registry(requireContext()).all()
        findPreference<ListPreference>("provider_id")?.apply {
            entries = providers.map { it.name }.toTypedArray()
            entryValues = providers.map { it.id }.toTypedArray()
        }
    }

    private fun importProvider(uri: Uri) {
        val ctx = requireContext()
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val buf = CharArray(MAX_PROVIDER_CHARS + 1)
                val n = r.read(buf)
                if (n > MAX_PROVIDER_CHARS) throw ProviderException("file is too large for a provider")
                if (n <= 0) "" else String(buf, 0, n)
            } ?: throw ProviderException("could not read the file")
            val id = ProviderStore.import(ctx, text)
            toast(getString(R.string.provider_imported, id))
            fillProviderList()
        } catch (e: ProviderException) {
            AlertDialog.Builder(ctx).setTitle(R.string.pref_import).setMessage(getString(R.string.provider_rejected, e.message.orEmpty())).setPositiveButton(R.string.ok, null).show()
        } catch (e: java.io.IOException) {
            toast(getString(R.string.provider_rejected, e.message.orEmpty()))
        }
    }

    private fun removeProviderDialog() {
        val ctx = requireContext()
        val ids = ProviderStore.importedIds(ctx)
        if (ids.isEmpty()) return toast(getString(R.string.no_imported_providers))
        AlertDialog.Builder(ctx).setTitle(R.string.pref_remove_provider)
            .setItems(ids.toTypedArray()) { _, which ->
                ProviderStore.remove(ctx, ids[which])
                if (GlobalSettings.of(ctx).providerId == ids[which]) findPreference<ListPreference>("provider_id")?.value = "open-meteo"
                fillProviderList()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Shows who the current weather data comes from (providers ask for this to be visible). */
    private fun showCredit() {
        val data = WeatherCache.load(requireContext())
        findPreference<Preference>("data_credit")?.summary = data?.attribution?.takeIf { it.isNotBlank() } ?: data?.providerId ?: getString(R.string.credit_none)
    }

    private fun firstUrl(text: String?): String? {
        val start = text?.indexOf("https://") ?: -1
        if (text == null || start < 0) return null
        return text.substring(start).takeWhile { !it.isWhitespace() }
    }

    private fun importAppleKey(uri: Uri) {
        val ctx = requireContext()
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val buf = CharArray(MAX_KEY_CHARS + 1)
                val n = r.read(buf)
                if (n > MAX_KEY_CHARS) throw ProviderException("file is too large for a key")
                if (n <= 0) "" else String(buf, 0, n)
            } ?: throw ProviderException("could not read the file")
            AppleTokens.saveKey(ctx, text)
            findPreference<Preference>("apple_import_key")?.summary = getString(R.string.apple_key_loaded)
            dirty = true
        } catch (e: ProviderException) {
            AlertDialog.Builder(ctx).setTitle(R.string.pref_apple_key).setMessage(getString(R.string.apple_key_rejected, e.message.orEmpty())).setPositiveButton(R.string.ok, null).show()
        } catch (e: java.io.IOException) {
            toast(getString(R.string.apple_key_rejected, e.message.orEmpty()))
        }
    }

    private fun importPictures(tree: Uri) {
        val ctx = requireContext().applicationContext
        toast(getString(R.string.pictures_importing))
        Thread {
            val r = Pictures.import(ctx, tree)
            WidgetUpdater.updateAll(ctx)
            activity?.runOnUiThread {
                toast(getString(R.string.pictures_done, r.imported, r.skipped))
                findPreference<Preference>("pictures_clear")?.summary = getString(R.string.pictures_count, Pictures.count(ctx))
            }
        }.start()
    }

    private fun fillFromDevice() {
        val ctx = requireContext()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
        if (best == null) return toast(getString(R.string.location_unknown))
        findPreference<EditTextPreference>("loc_lat")?.text = String.format(Locale.ROOT, "%.4f", best.latitude)
        findPreference<EditTextPreference>("loc_lon")?.text = String.format(Locale.ROOT, "%.4f", best.longitude)
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private companion object { const val MAX_PROVIDER_CHARS = 200_000; const val MAX_KEY_CHARS = 20_000 }
}
