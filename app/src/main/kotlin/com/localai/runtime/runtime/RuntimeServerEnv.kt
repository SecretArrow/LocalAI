package com.localai.runtime.runtime

import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.settings.SettingsRepository
import com.localai.runtime.server.api.ApiLimits
import com.localai.runtime.server.api.ServerEndpoint
import com.localai.runtime.server.api.ServerEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Server environment backed by live app settings. Transport configuration,
 * API limits and the embedded web console are resolved from the current
 * [AppSettings] snapshot (cached, refreshed in the background).
 */
class RuntimeServerEnv(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) : ServerEnv {

    @Volatile
    private var cached: AppSettings = AppSettings()

    init {
        scope.launch {
            settingsRepository.flow.collect { cached = it }
        }
    }

    override fun endpoint(): ServerEndpoint {
        val s = cached
        val host = if (s.apiLanAccess) "0.0.0.0" else s.apiHost.ifBlank { "127.0.0.1" }
        return ServerEndpoint(
            host = host,
            port = s.apiPort,
            tlsPort = if (s.apiTlsEnabled) s.apiTlsPort else null,
            tlsEnabled = s.apiTlsEnabled,
        )
    }

    override fun limits(): ApiLimits = ApiLimits(
        maxConcurrent = cached.apiMaxConcurrent,
        queueSize = cached.apiQueueSize,
        requestTimeoutSeconds = cached.apiTimeoutSeconds,
    )

    override fun webConsoleHtml(): String? = try {
        javaClass.classLoader?.getResourceAsStream("web/index.html")?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (t: Throwable) {
        null
    }
}
