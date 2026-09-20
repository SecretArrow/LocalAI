package com.localai.runtime.server

import com.localai.runtime.server.api.AuthStore
import com.localai.runtime.server.api.EngineException
import com.localai.runtime.server.api.InferenceEngine
import com.localai.runtime.server.api.ModelRegistry
import com.localai.runtime.server.api.ServerEnv
import com.localai.runtime.server.api.ServerLogEntry
import com.localai.runtime.server.api.ServerLogSink
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * The embedded OpenAI-compatible HTTP API server.
 *
 * Holds a CIO [ApplicationEngine] built from [applicationEngineEnvironment] with one plain HTTP
 * connector (from [ServerEnv.endpoint]) and, when TLS is requested, a second SSL connector on
 * `tlsPort` (default 8443) backed by an in-memory PKCS12 key store.
 *
 * All request handling is wired through [localAiModule] so that unit tests can install the exact
 * same pipeline via `ktor-server-test-host`.
 */
class ApiServer(
    private val registry: ModelRegistry,
    private val engine: InferenceEngine,
    private val auth: AuthStore,
    private val env: ServerEnv,
    private val logSink: ServerLogSink,
) {
    private val lock = Any()
    private val ring = RequestLogRing(RING_CAPACITY)
    private val state = ServerState()

    private var current: ApplicationEngine? = null

    @Volatile
    private var tlsActiveNow: Boolean = false

    /** Starts the plain HTTP server. Returns false when already running or when the port is in use. */
    fun start(): Boolean = startServer(tlsKeyStore = null, allowRestart = false)

    /** Stops the running server (both connectors when TLS was enabled). Idempotent. */
    fun stop() = synchronized(lock) {
        stopLocked()
    }

    fun isRunning(): Boolean = current != null

    /** The currently effective endpoint (mirrors the live TLS state once started). */
    fun endpoint(): ServerEndpoint {
        val configured = env.endpoint()
        return if (current != null && tlsActiveNow) {
            ServerEndpoint(
                host = configured.host,
                port = configured.port,
                tlsPort = configured.tlsPort ?: DEFAULT_TLS_PORT,
                tlsEnabled = true,
            )
        } else {
            configured
        }
    }

    /**
     * (Re)starts the server with an additional TLS connector on `tlsPort`. The plain HTTP
     * connector stays on the main port. Returns false when the certificate pair cannot be parsed
     * or a port is already in use.
     */
    suspend fun startWithTls(certPem: String, keyPem: String): Boolean {
        val keyStore = try {
            withContext(Dispatchers.Default) { SelfSignedCert.buildKeyStore(certPem, keyPem) }
        } catch (t: Throwable) {
            return false
        }
        return startServer(tlsKeyStore = keyStore, allowRestart = true)
    }

    /** Generates a fresh self-signed RSA-2048 certificate (3650 days) as a PEM pair (cert, key). */
    suspend fun generateSelfSignedCert(): Pair<String, String> = withContext(Dispatchers.Default) {
        SelfSignedCert.generate()
    }

    private fun startServer(tlsKeyStore: KeyStore?, allowRestart: Boolean): Boolean = synchronized(lock) {
        if (current != null) {
            if (!allowRestart) return false
            stopLocked()
        }
        val configured = env.endpoint()
        val host = configured.host.ifBlank { "0.0.0.0" }
        val httpPort = configured.port
        val tlsPort = configured.tlsPort ?: DEFAULT_TLS_PORT
        if (!portAvailable(host, httpPort)) return false
        if (tlsKeyStore != null && !portAvailable(host, tlsPort)) return false

        return try {
            val environment = applicationEngineEnvironment {
                watchPaths = emptyList()
                connector {
                    this.host = host
                    this.port = httpPort
                }
                if (tlsKeyStore != null) {
                    sslConnector(
                        keyStore = tlsKeyStore,
                        keyAlias = SelfSignedCert.KEY_ALIAS,
                        keyStorePassword = { SelfSignedCert.KEY_PASSWORD },
                        privateKeyPassword = { SelfSignedCert.KEY_PASSWORD },
                    ) {
                        this.host = host
                        this.port = tlsPort
                    }
                }
                module {
                    localAiModule(
                        registry = registry,
                        engine = engine,
                        auth = auth,
                        env = env,
                        logSink = logSink,
                        ring = ring,
                        state = state,
                        tlsActive = { tlsActiveNow },
                    )
                }
            }
            val server = embeddedServer(CIO, environment)
            server.start(wait = false)
            current = server
            tlsActiveNow = tlsKeyStore != null
            state.start()
            true
        } catch (t: Throwable) {
            // Bind failures (also caught proactively by the port probe above) and any other
            // engine start failure surface as `false`; the caller decides how to report it.
            current = null
            tlsActiveNow = false
            false
        }
    }

    private fun stopLocked() {
        val server = current
        current = null
        tlsActiveNow = false
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }

    private fun portAvailable(host: String, port: Int): Boolean = try {
        val address = InetAddress.getByName(host)
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 1)
            true
        } finally {
            socket.close()
        }
    } catch (t: Throwable) {
        false
    }

    companion object {
        const val RING_CAPACITY = 500
        const val DEFAULT_TLS_PORT = 8443
    }
}

/**
 * Installs the full LocalAI request pipeline into an [Application]:
 * ContentNegotiation (kotlinx JSON) -> request logging -> auth -> rate limit -> max body size ->
 * native routes -> OpenAI-compatible routes.
 *
 * `ring`, `state` and `tlsActive` are owned by [ApiServer] in production and supplied manually by
 * tests. `rateLimiter` is injectable so tests can use small limits.
 */
internal fun Application.localAiModule(
    registry: ModelRegistry,
    engine: InferenceEngine,
    auth: AuthStore,
    env: ServerEnv,
    logSink: ServerLogSink,
    ring: RequestLogRing,
    state: ServerState,
    tlsActive: () -> Boolean = { false },
    rateLimiter: RateLimiter = RateLimiter(),
) {
    install(ContentNegotiation) {
        json(moduleJson)
    }
    installRequestLogging(logSink, ring, state)
    installAuth(auth)
    installRateLimit(rateLimiter)
    installMaxBodySize(env)
    nativeRoutes(
        registry = registry,
        engine = engine,
        state = state,
        env = env,
        tlsActive = tlsActive,
    ) { limit -> ring.snapshot(limit) }
    openAiRoutes(
        engine = engine,
        registry = registry,
        maxBodyBytes = env.limits().maxRequestBytes,
    )
}

/** Shared JSON configuration for request/response bodies (mirrors the OpenAI leniency rules). */
internal val moduleJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class ApiErrorBody(
    val message: String,
    val type: String,
    val code: String? = null,
)

@Serializable
internal data class ApiErrorResponse(
    val error: ApiErrorBody,
)

internal suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    message: String,
    type: String,
    code: String? = null,
) {
    respondText(
        moduleJson.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse(ApiErrorBody(message, type, code))),
        ContentType.Application.Json,
        status,
    )
}

/** Maps [EngineException.Reason] to the HTTP status used by both native and OpenAI routes. */
internal fun engineErrorStatus(reason: EngineException.Reason): HttpStatusCode = when (reason) {
    EngineException.Reason.MODEL_NOT_FOUND -> HttpStatusCode.NotFound
    EngineException.Reason.BUSY -> HttpStatusCode.TooManyRequests
    EngineException.Reason.INSUFFICIENT_MEMORY -> HttpStatusCode(507, "Insufficient Storage")
    else -> HttpStatusCode.InternalServerError
}

internal suspend fun ApplicationCall.respondEngineError(e: EngineException) {
    respondApiError(
        status = engineErrorStatus(e.reason),
        message = e.message ?: "Engine error",
        type = "engine_error",
        code = e.reason.name.lowercase(),
    )
}

/**
 * Wraps a route handler body: [EngineException] maps to its HTTP status, anything else becomes a
 * generic 500. Never leaks stack traces; never double-responds after a response was committed.
 */
internal suspend inline fun ApplicationCall.withErrorHandling(block: ApplicationCall.() -> Unit) {
    try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: EngineException) {
        attributes.getOrNull(CallLogKey)?.error = e.message
        if (response.status() == null) respondEngineError(e)
    } catch (t: Throwable) {
        attributes.getOrNull(CallLogKey)?.error = t.message ?: t.javaClass.simpleName
        if (response.status() == null) {
            respondApiError(HttpStatusCode.InternalServerError, "Internal server error", "server_error")
        }
    }
}

/** Per-call mutable metadata the request logger reads after a handler completes. */
internal class CallLog {
    var model: String? = null
    var tokensIn: Int = 0
    var tokensOut: Int = 0
    var error: String? = null
}

internal val CallLogKey = AttributeKey<CallLog>("LocalAiCallLog")

/** Server-wide counters used by `GET /api/status`. */
internal class ServerState {
    val startedAtMs = AtomicLong(0)
    val requestsTotal = AtomicLong(0)
    val tokensGenerated = AtomicLong(0)

    fun start() {
        startedAtMs.set(System.currentTimeMillis())
    }

    fun uptimeSeconds(): Long {
        val start = startedAtMs.get()
        return if (start == 0L) 0L else (System.currentTimeMillis() - start) / 1000L
    }
}

/** In-memory ring of the most recent request log entries (bounded, mutex-pruned). */
internal class RequestLogRing(private val capacity: Int = ApiServer.RING_CAPACITY) {
    private val entries = ConcurrentLinkedDeque<ServerLogEntry>()
    private val pruneMutex = Mutex()

    suspend fun add(entry: ServerLogEntry) {
        entries.addLast(entry)
        if (entries.size > capacity) {
            pruneMutex.withLock {
                while (entries.size > capacity) {
                    entries.pollFirst()
                }
            }
        }
    }

    fun snapshot(limit: Int): List<ServerLogEntry> {
        if (limit <= 0) return emptyList()
        val copy = ArrayList(entries)
        return if (copy.size <= limit) copy else copy.takeLast(limit)
    }

    fun clear() {
        entries.clear()
    }
}

private fun Application.installRequestLogging(
    logSink: ServerLogSink,
    ring: RequestLogRing,
    state: ServerState,
) {
    intercept(ApplicationCallPipeline.Setup) {
        val callLog = CallLog()
        call.attributes.put(CallLogKey, callLog)
        val startNs = System.nanoTime()
        try {
            proceed()
        } finally {
            val entry = ServerLogEntry(
                ts = System.currentTimeMillis(),
                method = call.request.httpMethod.value,
                path = call.request.path(),
                status = call.response.status()?.value ?: 0,
                latencyMs = (System.nanoTime() - startNs) / 1_000_000L,
                model = callLog.model,
                client = call.request.origin.remoteHost,
                error = callLog.error,
                tokensIn = callLog.tokensIn,
                tokensOut = callLog.tokensOut,
            )
            state.requestsTotal.incrementAndGet()
            state.tokensGenerated.addAndGet(callLog.tokensOut.toLong())
            ring.add(entry)
            logSink.log(entry)
        }
    }
}

private fun Application.installMaxBodySize(env: ServerEnv) {
    intercept(ApplicationCallPipeline.Setup) {
        val maxBytes = env.limits().maxRequestBytes
        val declared = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBytes) {
            call.respondApiError(
                HttpStatusCode.PayloadTooLarge,
                "Request body too large (limit is $maxBytes bytes)",
                "invalid_request_error",
                "request_too_large",
            )
        } else {
            proceed()
        }
    }
}
