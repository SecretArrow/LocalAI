package com.localai.runtime.runtime

import com.localai.runtime.core.db.ApiLogEntity
import com.localai.runtime.core.db.LocalAiDatabase
import com.localai.runtime.core.log.AppLogger
import com.localai.runtime.server.api.ServerLogEntry
import com.localai.runtime.server.api.ServerLogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists API request logs into Room (bounded) and mirrors a compact line
 * into the application log. Privacy mode never logs bodies — only metadata.
 */
class RuntimeLogSink(
    private val db: LocalAiDatabase,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : ServerLogSink {

    override fun log(entry: ServerLogEntry) {
        logger.i(
            tag = "ApiServer",
            msg = "${entry.method} ${entry.path} -> ${entry.status} (${entry.latencyMs}ms)" +
                (entry.model?.let { " model=$it" } ?: ""),
        )
        scope.launch {
            try {
                db.apiLogDao().insert(
                    ApiLogEntity(
                        ts = entry.ts,
                        method = entry.method,
                        path = entry.path,
                        status = entry.status,
                        latencyMs = entry.latencyMs,
                        model = entry.model,
                        client = entry.client,
                        error = entry.error,
                        tokensIn = entry.tokensIn,
                        tokensOut = entry.tokensOut,
                    ),
                )
            } catch (_: Throwable) {
                // Logging must never take the server down.
            }
        }
    }
}
