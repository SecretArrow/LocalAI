package com.localai.runtime.core.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.model.DarkMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "localai_settings")

/**
 * DataStore-backed [AppSettings] persistence. Every field round-trips with its declared default:
 * booleans/ints/longs/strings stored natively, enums as names, nullable strings as absent keys.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val defaultBackendId = stringPreferencesKey("defaultBackendId")
        val defaultThreads = intPreferencesKey("defaultThreads")
        val defaultContext = intPreferencesKey("defaultContext")
        val defaultGpuLayers = intPreferencesKey("defaultGpuLayers")
        val defaultModelId = stringPreferencesKey("defaultModelId")
        val apiEnabled = booleanPreferencesKey("apiEnabled")
        val apiHost = stringPreferencesKey("apiHost")
        val apiPort = intPreferencesKey("apiPort")
        val apiTlsEnabled = booleanPreferencesKey("apiTlsEnabled")
        val apiTlsPort = intPreferencesKey("apiTlsPort")
        val apiAuthMode = stringPreferencesKey("apiAuthMode")
        val apiMaxConcurrent = intPreferencesKey("apiMaxConcurrent")
        val apiQueueSize = intPreferencesKey("apiQueueSize")
        val apiTimeoutSeconds = longPreferencesKey("apiTimeoutSeconds")
        val apiLanAccess = booleanPreferencesKey("apiLanAccess")
        val mdnsEnabled = booleanPreferencesKey("mdnsEnabled")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val maxParallelDownloads = intPreferencesKey("maxParallelDownloads")
        val autoRetry = booleanPreferencesKey("autoRetry")
        val autoResume = booleanPreferencesKey("autoResume")
        val keepRuntimeAlive = booleanPreferencesKey("keepRuntimeAlive")
        val startOnBoot = booleanPreferencesKey("startOnBoot")
        val autoStartModelId = stringPreferencesKey("autoStartModelId")
        val catalogUrl = stringPreferencesKey("catalogUrl")
        val catalogAutoRefresh = booleanPreferencesKey("catalogAutoRefresh")
        val catalogRefreshHours = intPreferencesKey("catalogRefreshHours")
        val darkMode = stringPreferencesKey("darkMode")
        val dynamicColor = booleanPreferencesKey("dynamicColor")
        val developerMode = booleanPreferencesKey("developerMode")
        val verboseLogs = booleanPreferencesKey("verboseLogs")
        val privacyMode = booleanPreferencesKey("privacyMode")
    }

    /** Live settings stream; emits defaults when nothing has been persisted yet. */
    val flow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs.toAppSettings() }

    /** Applies [transform] to the current settings and persists every field. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings())
            prefs.clear()
            writeSettings(prefs, next)
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            defaultBackendId = this[Keys.defaultBackendId],
            defaultThreads = this[Keys.defaultThreads] ?: defaults.defaultThreads,
            defaultContext = this[Keys.defaultContext] ?: defaults.defaultContext,
            defaultGpuLayers = this[Keys.defaultGpuLayers] ?: defaults.defaultGpuLayers,
            defaultModelId = this[Keys.defaultModelId],
            apiEnabled = this[Keys.apiEnabled] ?: defaults.apiEnabled,
            apiHost = this[Keys.apiHost] ?: defaults.apiHost,
            apiPort = this[Keys.apiPort] ?: defaults.apiPort,
            apiTlsEnabled = this[Keys.apiTlsEnabled] ?: defaults.apiTlsEnabled,
            apiTlsPort = this[Keys.apiTlsPort] ?: defaults.apiTlsPort,
            apiAuthMode = enumOrDefault(this[Keys.apiAuthMode], defaults.apiAuthMode),
            apiMaxConcurrent = this[Keys.apiMaxConcurrent] ?: defaults.apiMaxConcurrent,
            apiQueueSize = this[Keys.apiQueueSize] ?: defaults.apiQueueSize,
            apiTimeoutSeconds = this[Keys.apiTimeoutSeconds] ?: defaults.apiTimeoutSeconds,
            apiLanAccess = this[Keys.apiLanAccess] ?: defaults.apiLanAccess,
            mdnsEnabled = this[Keys.mdnsEnabled] ?: defaults.mdnsEnabled,
            wifiOnly = this[Keys.wifiOnly] ?: defaults.wifiOnly,
            maxParallelDownloads = this[Keys.maxParallelDownloads] ?: defaults.maxParallelDownloads,
            autoRetry = this[Keys.autoRetry] ?: defaults.autoRetry,
            autoResume = this[Keys.autoResume] ?: defaults.autoResume,
            keepRuntimeAlive = this[Keys.keepRuntimeAlive] ?: defaults.keepRuntimeAlive,
            startOnBoot = this[Keys.startOnBoot] ?: defaults.startOnBoot,
            autoStartModelId = this[Keys.autoStartModelId],
            catalogUrl = this[Keys.catalogUrl] ?: defaults.catalogUrl,
            catalogAutoRefresh = this[Keys.catalogAutoRefresh] ?: defaults.catalogAutoRefresh,
            catalogRefreshHours = this[Keys.catalogRefreshHours] ?: defaults.catalogRefreshHours,
            darkMode = enumOrDefault(this[Keys.darkMode], defaults.darkMode),
            dynamicColor = this[Keys.dynamicColor] ?: defaults.dynamicColor,
            developerMode = this[Keys.developerMode] ?: defaults.developerMode,
            verboseLogs = this[Keys.verboseLogs] ?: defaults.verboseLogs,
            privacyMode = this[Keys.privacyMode] ?: defaults.privacyMode,
        )
    }

    private fun writeSettings(prefs: MutablePreferences, settings: AppSettings) {
        settings.defaultBackendId?.let { prefs[Keys.defaultBackendId] = it }
        prefs[Keys.defaultThreads] = settings.defaultThreads
        prefs[Keys.defaultContext] = settings.defaultContext
        prefs[Keys.defaultGpuLayers] = settings.defaultGpuLayers
        settings.defaultModelId?.let { prefs[Keys.defaultModelId] = it }
        prefs[Keys.apiEnabled] = settings.apiEnabled
        prefs[Keys.apiHost] = settings.apiHost
        prefs[Keys.apiPort] = settings.apiPort
        prefs[Keys.apiTlsEnabled] = settings.apiTlsEnabled
        prefs[Keys.apiTlsPort] = settings.apiTlsPort
        prefs[Keys.apiAuthMode] = settings.apiAuthMode.name
        prefs[Keys.apiMaxConcurrent] = settings.apiMaxConcurrent
        prefs[Keys.apiQueueSize] = settings.apiQueueSize
        prefs[Keys.apiTimeoutSeconds] = settings.apiTimeoutSeconds
        prefs[Keys.apiLanAccess] = settings.apiLanAccess
        prefs[Keys.mdnsEnabled] = settings.mdnsEnabled
        prefs[Keys.wifiOnly] = settings.wifiOnly
        prefs[Keys.maxParallelDownloads] = settings.maxParallelDownloads
        prefs[Keys.autoRetry] = settings.autoRetry
        prefs[Keys.autoResume] = settings.autoResume
        prefs[Keys.keepRuntimeAlive] = settings.keepRuntimeAlive
        prefs[Keys.startOnBoot] = settings.startOnBoot
        settings.autoStartModelId?.let { prefs[Keys.autoStartModelId] = it }
        prefs[Keys.catalogUrl] = settings.catalogUrl
        prefs[Keys.catalogAutoRefresh] = settings.catalogAutoRefresh
        prefs[Keys.catalogRefreshHours] = settings.catalogRefreshHours
        prefs[Keys.darkMode] = settings.darkMode.name
        prefs[Keys.dynamicColor] = settings.dynamicColor
        prefs[Keys.developerMode] = settings.developerMode
        prefs[Keys.verboseLogs] = settings.verboseLogs
        prefs[Keys.privacyMode] = settings.privacyMode
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        if (value == null) {
            fallback
        } else {
            runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
        }
}
