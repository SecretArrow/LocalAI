package com.localai.runtime.server

import com.localai.runtime.server.api.EngineStats
import com.localai.runtime.server.api.InferenceEngine
import com.localai.runtime.server.api.ModelRegistry
import com.localai.runtime.server.api.ServerEnv
import com.localai.runtime.server.api.ServerLogEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class BackendInfo(
    val id: String,
    val available: Boolean,
    val note: String,
)

@Serializable
internal data class ModelsResponse(val models: List<com.localai.runtime.server.api.ServerModel>)

@Serializable
internal data class RuntimeResponse(val running: List<EngineStats>, val cpu: BackendInfo)

@Serializable
internal data class BackendsResponse(val backends: List<BackendInfo>)

@Serializable
internal data class DownloadsResponse(val downloads: List<String>, val note: String)

@Serializable
internal data class DevicesResponse(val devices: List<String>, val note: String)

@Serializable
internal data class LogsResponse(val logs: List<ServerLogEntry>)

/**
 * Native management API (spec §16). `/api/health` is intentionally unauthenticated; every other
 * endpoint passes through the auth interceptor installed by [localAiModule].
 *
 * `logsSupplier` is a lambda over the ApiServer request-log ring: `(limit) -> last N entries`.
 */
internal fun Application.nativeRoutes(
    registry: ModelRegistry,
    engine: InferenceEngine,
    state: ServerState,
    env: ServerEnv,
    tlsActive: () -> Boolean,
    logsSupplier: (limit: Int) -> List<ServerLogEntry>,
) {
    routing {
        get("/api/health") {
            call.respondText("""{"status":"ok","version":1}""", ContentType.Application.Json)
        }

        get("/api/status") {
            call.withErrorHandling {
                val running = try {
                    engine.listRunning()
                } catch (t: Throwable) {
                    emptyList()
                }
                val payload = buildJsonObject {
                    put("uptime_seconds", state.uptimeSeconds())
                    put("models_running", JsonArray(running.map { JsonPrimitive(it) }))
                    put("requests_total", state.requestsTotal.get())
                    put("tokens_generated", state.tokensGenerated.get())
                    put("port", env.endpoint().port)
                    put("tls", tlsActive())
                }
                respondText(payload.toString(), ContentType.Application.Json)
            }
        }

        get("/api/models") {
            call.withErrorHandling {
                respond(ModelsResponse(models = registry.list()))
            }
        }

        get("/api/models/{id}") {
            call.withErrorHandling {
                val id = parameters["id"]
                val model = id?.let { registry.get(it) }
                if (model == null) {
                    respondApiError(HttpStatusCode.NotFound, "Model not found", "not_found_error", "model_not_found")
                } else {
                    respond(model)
                }
            }
        }

        post("/api/models/{id}/start") {
            call.withErrorHandling {
                val id = parameters["id"]
                if (id == null) {
                    respondApiError(HttpStatusCode.BadRequest, "Missing model id", "invalid_request_error")
                } else {
                    respond(engine.start(id))
                }
            }
        }

        post("/api/models/{id}/stop") {
            call.withErrorHandling {
                val id = parameters["id"]
                if (id == null) {
                    respondApiError(HttpStatusCode.BadRequest, "Missing model id", "invalid_request_error")
                } else {
                    respond(engine.stop(id))
                }
            }
        }

        post("/api/models/{id}/restart") {
            call.withErrorHandling {
                val id = parameters["id"]
                if (id == null) {
                    respondApiError(HttpStatusCode.BadRequest, "Missing model id", "invalid_request_error")
                } else {
                    respond(engine.restart(id))
                }
            }
        }

        get("/api/runtime") {
            call.withErrorHandling {
                val running = withTimeoutOrNull(1500) { engine.stats().first() } ?: emptyList()
                respond(
                    RuntimeResponse(
                        running = running,
                        cpu = BackendInfo(
                            id = "cpu",
                            available = true,
                            note = "CPU runtime (llama.cpp) is bundled and active in this build",
                        ),
                    ),
                )
            }
        }

        get("/api/backends") {
            call.withErrorHandling {
                respond(
                    BackendsResponse(
                        backends = listOf(
                            BackendInfo("cpu", true, "CPU runtime (llama.cpp) is bundled in this build"),
                            BackendInfo("vulkan", false, "not bundled in this build"),
                            BackendInfo("opencl", false, "not bundled in this build"),
                            BackendInfo("nnapi", false, "not bundled in this build"),
                            BackendInfo("npu", false, "not bundled in this build"),
                        ),
                    ),
                )
            }
        }

        get("/api/downloads") {
            call.withErrorHandling {
                respond(
                    DownloadsResponse(
                        downloads = emptyList(),
                        note = "download manager is app-local; the server has no download visibility",
                    ),
                )
            }
        }

        get("/api/devices") {
            call.withErrorHandling {
                respond(
                    DevicesResponse(
                        devices = emptyList(),
                        note = "paired devices are managed in the app and are not exposed via this API",
                    ),
                )
            }
        }

        get("/api/logs") {
            call.withErrorHandling {
                val limit = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                respond(LogsResponse(logs = logsSupplier(limit)))
            }
        }

        webConsole { env.webConsoleHtml() }

        get("/favicon.ico") {
            call.respondText(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><rect width="16" height="16" rx="3" fill="#0f1412"/><circle cx="8" cy="8" r="4" fill="#52ddd0"/></svg>""",
                ContentType.parse("image/svg+xml"),
            )
        }
    }
}
