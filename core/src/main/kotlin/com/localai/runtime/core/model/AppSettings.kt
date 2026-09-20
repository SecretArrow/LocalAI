package com.localai.runtime.core.model

import kotlinx.serialization.Serializable

/**
 * All application settings. Persisted via DataStore by [com.localai.runtime.core.settings.SettingsRepository].
 * Mirrors the Settings screen sections (Runtime, API, Downloads, Background, Security, Storage, Appearance, Developer).
 */
@Serializable
data class AppSettings(
    // Runtime defaults
    val defaultBackendId: String? = null, // null = Auto
    val defaultThreads: Int = -1,
    val defaultContext: Int = 4096,
    val defaultGpuLayers: Int = -1,
    val defaultModelId: String? = null,
    // API server
    val apiEnabled: Boolean = false,
    val apiHost: String = "127.0.0.1",
    val apiPort: Int = 8080,
    val apiTlsEnabled: Boolean = false,
    val apiTlsPort: Int = 8443,
    val apiAuthMode: AuthMode = AuthMode.NONE,
    val apiMaxConcurrent: Int = 4,
    val apiQueueSize: Int = 16,
    val apiTimeoutSeconds: Long = 300,
    val apiLanAccess: Boolean = false,
    // Discovery
    val mdnsEnabled: Boolean = true,
    // Downloads
    val wifiOnly: Boolean = true,
    val maxParallelDownloads: Int = 2,
    val autoRetry: Boolean = true,
    val autoResume: Boolean = true,
    // Background
    val keepRuntimeAlive: Boolean = true,
    val startOnBoot: Boolean = false,
    val autoStartModelId: String? = null,
    // Catalog
    val catalogUrl: String = DEFAULT_CATALOG_URL,
    val catalogAutoRefresh: Boolean = true,
    val catalogRefreshHours: Int = 24,
    // Appearance
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = true,
    // Developer & privacy
    val developerMode: Boolean = false,
    val verboseLogs: Boolean = false,
    val privacyMode: Boolean = true,
) {
    companion object {
        const val DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/SecretArrow/LocalAI/main/catalog/catalog.json"
    }
}
