package com.localai.runtime.server

import com.localai.runtime.server.api.ApiLimits
import com.localai.runtime.server.api.AuthStore
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import com.localai.runtime.server.api.EngineException
import com.localai.runtime.server.api.EngineStats
import com.localai.runtime.server.api.InferenceEngine
import com.localai.runtime.server.api.ModelRegistry
import com.localai.runtime.server.api.ServerEndpoint
import com.localai.runtime.server.api.ServerEnv
import com.localai.runtime.server.api.ServerLogEntry
import com.localai.runtime.server.api.ServerLogSink
import com.localai.runtime.server.api.ServerModel
import io.ktor.http.contentType
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consolidated api-server tests (auth plugin + OpenAI routes + rate limiter + native routes),
 * all wired through the same [localAiModule] used by [ApiServer].
 */
class ApiServerTest {

    // ------------------------------------------------------------------ fakes

    private class FakeRegistry : ModelRegistry {
        val models = mutableListOf(
            ServerModel(id = "m1", name = "Model One", format = "gguf", state = "running", backend = "cpu"),
            ServerModel(id = "m2", name = "Model Two", format = "gguf", state = "stopped"),
        )

        override suspend fun list(): List<ServerModel> = models.toList()

        override suspend fun get(id: String): ServerModel? = models.firstOrNull { it.id == id }
    }

    private class FakeEngine : InferenceEngine {
        val running = mutableSetOf("m1")

        override suspend fun listRunning(): List<String> = running.toList()

        override suspend fun start(modelId: String): ServerModel {
            if (modelId == "broken") {
                throw EngineException("start failed: not enough memory", EngineException.Reason.INSUFFICIENT_MEMORY)
            }
            if (modelId != "m1" && modelId != "m2") {
                throw EngineException("model not found", EngineException.Reason.MODEL_NOT_FOUND)
            }
            running.add(modelId)
            return ServerModel(id = modelId, name = modelId, format = "gguf", state = "running", backend = "cpu")
        }

        override suspend fun stop(modelId: String): ServerModel {
            running.remove(modelId)
            return ServerModel(id = modelId, name = modelId, format = "gguf", state = "stopped")
        }

        override suspend fun restart(modelId: String): ServerModel {
            running.add(modelId)
            return ServerModel(id = modelId, name = modelId, format = "gguf", state = "running", backend = "cpu")
        }

        override fun generate(
            modelId: String,
            messages: List<ChatMessage>,
            params: CompletionParams,
        ): Flow<String> = flow {
            emit("Hello")
            emit(" world")
        }

        override suspend fun benchmark(modelId: String): BenchmarkResult =
            BenchmarkResult(modelId, "cpu", 100.0, 50.0, 0L, 0L)

        override fun stats(): Flow<List<EngineStats>> =
            flowOf(listOf(EngineStats(modelId = "m1", state = "running", backend = "cpu")))
    }

    private class FakeAuth(private val authMode: String) : AuthStore {
        override fun mode(): String = authMode

        override suspend fun verify(token: String): Boolean = token == "secret"
    }

    private class FakeEnv(private val html: String? = null) : ServerEnv {
        override fun endpoint(): ServerEndpoint = ServerEndpoint(host = "127.0.0.1", port = 8080)

        override fun limits(): ApiLimits = ApiLimits()

        override fun webConsoleHtml(): String? = html
    }

    private class CollectingSink : ServerLogSink {
        val entries = mutableListOf<ServerLogEntry>()

        override fun log(entry: ServerLogEntry) {
            entries.add(entry)
        }
    }

    /** Wires the module exactly like ApiServer does; every knob is per-test configurable. */
    private class Fixture(
        val registry: FakeRegistry = FakeRegistry(),
        val engine: FakeEngine = FakeEngine(),
        val auth: FakeAuth = FakeAuth("BEARER"),
        val env: FakeEnv = FakeEnv(),
        val sink: CollectingSink = CollectingSink(),
        val ring: RequestLogRing = RequestLogRing(),
        val state: ServerState = ServerState().apply { start() },
        val rateLimiter: RateLimiter = RateLimiter(),
    ) {
        fun install(app: Application) {
            app.localAiModule(
                registry = registry,
                engine = engine,
                auth = auth,
                env = env,
                logSink = sink,
                ring = ring,
                state = state,
                tlsActive = { false },
                rateLimiter = rateLimiter,
            )
        }
    }

    // -------------------------------------------------------------- helpers

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun chatBody(model: String, stream: Boolean = false): String =
        """{"model":"$model","stream":$stream,"messages":[{"role":"user","content":"Say hi"}]}"""

    // ---------------------------------------------------------------- tests

    @Test
    fun healthIsPublicAndReturnsOk() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.get("/api/health")
        assertEquals(200, response.status?.value)
        val body = parse(response.bodyAsText())
        assertEquals("ok", body.string("status"))
        assertEquals(1, body.int("version"))
    }

    @Test
    fun openAiModelsRejectMissingToken() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.get("/v1/models")
        assertEquals(401, response.status?.value)
        val error = parse(response.bodyAsText()).obj("error")!!
        assertEquals("Invalid or missing API token", error.string("message"))
        assertEquals("authentication_error", error.string("type"))
        assertEquals("invalid_api_key", error.string("code"))
    }

    @Test
    fun openAiModelsAcceptValidBearerToken() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.get("/v1/models") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, response.status?.value)
        val body = parse(response.bodyAsText())
        assertEquals("list", body.string("object"))
        val data = body.array("data")!!
        assertEquals(2, data.size)
        for (element in data) {
            val m = element.jsonObject
            assertEquals("model", m.string("object"))
            assertEquals("local", m.string("owned_by"))
            assertNotNull(m.string("id"))
        }
    }

    @Test
    fun authModeNoneAllowsEverything() = testApplication {
        val f = Fixture(auth = FakeAuth("NONE"))
        application { f.install(this) }
        val response = client.get("/v1/models")
        assertEquals(200, response.status?.value)
    }

    @Test
    fun apiKeyModeAcceptsHeaderAndBearer() = testApplication {
        val f = Fixture(auth = FakeAuth("API_KEY"))
        application { f.install(this) }

        val viaHeader = client.get("/v1/models") {
            headers { append("X-API-Key", "secret") }
        }
        assertEquals(200, viaHeader.status?.value)

        val viaBearer = client.get("/v1/models") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, viaBearer.status?.value)

        val wrong = client.get("/v1/models") {
            headers { append("X-API-Key", "nope") }
        }
        assertEquals(401, wrong.status?.value)
    }

    @Test
    fun chatCompletionNonStreamReturnsFullResponse() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.post("/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody(chatBody("m1"))
        }
        assertEquals(200, response.status?.value)
        val body = parse(response.bodyAsText())
        assertEquals("chat.completion", body.string("object"))
        assertEquals("m1", body.string("model"))
        assertTrue(body.string("id")!!.startsWith("chatcmpl-"))

        val choice = body.array("choices")!!.first().jsonObject
        assertEquals("stop", choice.string("finish_reason"))
        assertEquals("Hello world", choice.obj("message")!!.string("content"))

        val usage = body.obj("usage")!!
        assertEquals(2, usage.int("completion_tokens"))
        assertTrue((usage.int("total_tokens") ?: 0) >= 2)
    }

    @Test
    fun chatCompletionStreamEmitsSseChunks() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.post("/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody(chatBody("m1", stream = true))
        }
        assertEquals(200, response.status?.value)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.contains("text/event-stream"))
        val text = response.bodyAsText()
        assertTrue(text.contains("data: "))
        assertTrue(text.contains("\"object\":\"chat.completion.chunk\""))
        assertTrue(text.contains("\"delta\":{\"content\":\"Hello\"}"))
        assertTrue(text.contains("\"delta\":{\"content\":\" world\"}"))
        assertTrue(text.contains("\"finish_reason\":\"stop\""))
        assertTrue(text.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun chatCompletionUnknownModelIs404ModelNotFound() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.post("/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody(chatBody("ghost"))
        }
        assertEquals(404, response.status?.value)
        val error = parse(response.bodyAsText()).obj("error")!!
        assertEquals("The model `ghost` was not found or is not running", error.string("message"))
        assertEquals("invalid_request_error", error.string("type"))
        assertEquals("model_not_found", error.string("code"))
    }

    @Test
    fun invalidJsonBodyIs400() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.post("/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody("this is not json")
        }
        assertEquals(400, response.status?.value)
        val error = parse(response.bodyAsText()).obj("error")!!
        assertEquals("Invalid JSON body", error.string("message"))
        assertEquals("invalid_request_error", error.string("type"))
    }

    @Test
    fun embeddingsReturns501NotSupported() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        val response = client.post("/v1/embeddings") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m1","input":"hello"}""")
        }
        assertEquals(501, response.status?.value)
        val error = parse(response.bodyAsText()).obj("error")!!
        assertEquals(
            "Embeddings require a runtime with embedding support; not available in this build",
            error.string("message"),
        )
        assertEquals("not_supported_error", error.string("type"))
    }

    @Test
    fun legacyCompletionsAcceptStringAndArrayPrompt() = testApplication {
        val f = Fixture()
        application { f.install(this) }

        val stringPrompt = client.post("/v1/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m1","prompt":"Hello there"}""")
        }
        assertEquals(200, stringPrompt.status?.value)
        val body = parse(stringPrompt.bodyAsText())
        assertEquals("text_completion", body.string("object"))
        assertTrue(body.string("id")!!.startsWith("cmpl-"))
        assertEquals("Hello world", body.array("choices")!!.first().jsonObject.string("text"))

        val arrayPrompt = client.post("/v1/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m1","prompt":["first line","second line"]}""")
        }
        assertEquals(200, arrayPrompt.status?.value)
        assertEquals("Hello world", parse(arrayPrompt.bodyAsText()).array("choices")!!.first().jsonObject.string("text"))
    }

    @Test
    fun generateEndpointsAreRateLimited() = testApplication {
        val f = Fixture(rateLimiter = RateLimiter(generatePerMin = 2))
        application { f.install(this) }
        var ok = 0
        var limited = 0
        repeat(5) {
            val response = client.post("/v1/chat/completions") {
                headers { append(HttpHeaders.Authorization, "Bearer secret") }
                contentType(ContentType.Application.Json)
                setBody(chatBody("m1"))
            }
            when (response.status?.value) {
                200 -> ok++
                429 -> limited++
            }
        }
        assertEquals(2, ok)
        assertTrue("expected at least one 429, got $limited", limited >= 1)
    }

    @Test
    fun nativeModelsListAndDetail() = testApplication {
        val f = Fixture()
        application { f.install(this) }

        val list = client.get("/api/models") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, list.status?.value)
        assertEquals(2, parse(list.bodyAsText()).array("models")!!.size)

        val detail = client.get("/api/models/m2") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, detail.status?.value)
        assertEquals("m2", parse(detail.bodyAsText()).string("id"))

        val missing = client.get("/api/models/zzz") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(404, missing.status?.value)
    }

    @Test
    fun modelStartStopMapsEngineExceptions() = testApplication {
        val f = Fixture()
        application { f.install(this) }

        val start = client.post("/api/models/m2/start") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, start.status?.value)
        assertEquals("running", parse(start.bodyAsText()).string("state"))

        val status = client.get("/api/status") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        val running = parse(status.bodyAsText()).array("models_running")!!
        assertTrue(running.map { (it as JsonPrimitive).content }.contains("m2"))

        val stop = client.post("/api/models/m2/stop") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, stop.status?.value)
        assertEquals("stopped", parse(stop.bodyAsText()).string("state"))

        val failed = client.post("/api/models/broken/start") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(507, failed.status?.value)
        val error = parse(failed.bodyAsText()).obj("error")!!
        assertEquals("insufficient_memory", error.string("code"))
    }

    @Test
    fun statusExposesCountersAndTransport() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        client.get("/api/health")
        val response = client.get("/api/status") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, response.status?.value)
        val body = parse(response.bodyAsText())
        assertTrue((body.long("uptime_seconds") ?: -1L) >= 0L)
        assertTrue((body.long("requests_total") ?: 0L) >= 1L)
        assertTrue((body.long("tokens_generated") ?: -1L) >= 0L)
        assertEquals(8080, body.int("port"))
        assertEquals(false, body.bool("tls"))
    }

    @Test
    fun logsEndpointReturnsRecentEntriesWithLimit() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        repeat(3) { client.get("/api/health") }

        val response = client.get("/api/logs?limit=100") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, response.status?.value)
        val logs = parse(response.bodyAsText()).array("logs")!!
        assertTrue("expected log entries", logs.size >= 3)
        val first = logs.first().jsonObject
        assertEquals("GET", first.string("method"))
        assertEquals("/api/health", first.string("path"))
        assertEquals(200, first.int("status"))
        assertTrue((first.long("latencyMs") ?: -1L) >= 0L)
        assertNotNull(first.long("ts"))

        val limited = client.get("/api/logs?limit=2") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(2, parse(limited.bodyAsText()).array("logs")!!.size)
    }

    @Test
    fun requestLogSinkReceivesEntries() = testApplication {
        val f = Fixture()
        application { f.install(this) }
        client.post("/v1/chat/completions") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
            contentType(ContentType.Application.Json)
            setBody(chatBody("m1"))
        }
        assertTrue(f.sink.entries.isNotEmpty())
        val chatEntry = f.sink.entries.first { it.path == "/v1/chat/completions" }
        assertEquals("POST", chatEntry.method)
        assertEquals("m1", chatEntry.model)
        assertEquals(2, chatEntry.tokensOut)
        assertTrue(chatEntry.tokensIn > 0)
        assertNotNull(chatEntry.client)
    }

    @Test
    fun webConsoleServedWithoutAuthAndFaviconIsSafe() = testApplication {
        val f = Fixture(env = FakeEnv(html = "<html><body>LocalAI console</body></html>"))
        application { f.install(this) }

        val console = client.get("/")
        assertEquals(200, console.status?.value)
        assertTrue(console.headers[HttpHeaders.ContentType]!!.contains("text/html"))
        assertEquals("no-store", console.headers[HttpHeaders.CacheControl])
        assertTrue(console.bodyAsText().contains("LocalAI console"))

        val favicon = client.get("/favicon.ico")
        assertEquals(200, favicon.status?.value)
    }

    @Test
    fun runtimeAndBackendsAreHonest() = testApplication {
        val f = Fixture()
        application { f.install(this) }

        val runtime = client.get("/api/runtime") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, runtime.status?.value)
        val runtimeBody = parse(runtime.bodyAsText())
        assertEquals("m1", runtimeBody.array("running")!!.first().jsonObject.string("modelId"))
        assertEquals("cpu", runtimeBody.obj("cpu")!!.string("id"))

        val backends = client.get("/api/backends") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, backends.status?.value)
        val list = parse(backends.bodyAsText()).array("backends")!!
        val byId = list.map { it.jsonObject.string("id") to it.jsonObject }
        assertEquals(5, byId.size)
        assertTrue(byId.first { it.first == "cpu" }.second.bool("available") == true)
        assertTrue(byId.first { it.first == "vulkan" }.second.bool("available") == false)

        val downloads = client.get("/api/downloads") {
            headers { append(HttpHeaders.Authorization, "Bearer secret") }
        }
        assertEquals(200, downloads.status?.value)
        assertEquals(0, parse(downloads.bodyAsText()).array("downloads")!!.size)
    }
}
