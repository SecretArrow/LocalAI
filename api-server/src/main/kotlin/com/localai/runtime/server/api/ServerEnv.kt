package com.localai.runtime.server.api

/** Read-only model catalog access for the API server. */
interface ModelRegistry {
    suspend fun list(): List<ServerModel>
    suspend fun get(id: String): ServerModel?
}

/** Authentication contract used by the server's auth plugin. */
interface AuthStore {
    /** One of NONE, API_KEY, BEARER. */
    fun mode(): String

    /** Constant-time verification of a presented token. Returns false when auth is NONE. */
    suspend fun verify(token: String): Boolean
}

/** Server-side environment: transport config, limits and the embedded web console. */
interface ServerEnv {
    fun endpoint(): ServerEndpoint
    fun limits(): ApiLimits
    fun webConsoleHtml(): String?
}

/** Sink for structured request logs produced by the server. */
interface ServerLogSink {
    fun log(entry: ServerLogEntry)
}
