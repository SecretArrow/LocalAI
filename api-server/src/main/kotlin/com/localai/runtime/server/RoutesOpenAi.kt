package com.localai.runtime.server

import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import com.localai.runtime.server.api.InferenceEngine
import com.localai.runtime.server.api.ModelRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.WriterScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

// ---------------------------------------------------------------------------
// OpenAI-compatible request/response DTOs (kotlinx-serialization, lenient).
// ---------------------------------------------------------------------------

@Serializable
private data class OpenAiMessage(val role: String = "user", val content: String = "")

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage> = emptyList(),
    val stream: Boolean = false,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val top_p: Double? = null,
    val stop: JsonElement? = null,
)

@Serializable
private data class OpenAiCompletionRequest(
    val model: String,
    val prompt: JsonElement? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val top_p: Double? = null,
    val stop: JsonElement? = null,
)

@Serializable
private data class OpenAiModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    val owned_by: String = "local",
)

@Serializable
private data class OpenAiModelList(
    val `object`: String = "list",
    val data: List<OpenAiModelInfo>,
)

@Serializable
private data class UsageInfo(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
)

@Serializable
private data class ChatCompletionChoice(
    val index: Int,
    val message: OpenAiMessage,
    val finish_reason: String?,
)

@Serializable
private data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: UsageInfo,
)

@Serializable
private data class DeltaContent(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
private data class ChunkChoice(
    val index: Int = 0,
    val delta: DeltaContent,
    val finish_reason: String? = null,
)

@Serializable
private data class ChatCompletionChunk(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
)

@Serializable
private data class TextCompletionChoice(
    val text: String,
    val index: Int = 0,
    val finish_reason: String? = null,
)

@Serializable
private data class TextCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<TextCompletionChoice>,
    val usage: UsageInfo? = null,
)

/** JSON used to serialize SSE chunks: keep defaults (`index`), drop explicit nulls. */
private val sseJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val secureRandom = SecureRandom()

/**
 * OpenAI-compatible API (spec §15). Model resolution is honest: only models that are currently
 * running in the engine are served; anything else is a 404 `model_not_found` (the server never
 * auto-starts models).
 */
internal fun Application.openAiRoutes(
    engine: InferenceEngine,
    registry: ModelRegistry,
    maxBodyBytes: Long,
) {
    routing {
        get("/v1/models") {
            call.withErrorHandling {
                respond(
                    OpenAiModelList(data = registry.list().map { OpenAiModelInfo(id = it.id) }),
                )
            }
        }

        post("/v1/chat/completions") {
            val callLog = call.attributes.getOrNull(CallLogKey)
            call.withErrorHandling {
                val raw = receiveText()
                if (raw.length > maxBodyBytes) {
                    respondApiError(
                        HttpStatusCode.PayloadTooLarge,
                        "Request body too large (limit is $maxBodyBytes bytes)",
                        "invalid_request_error",
                        "request_too_large",
                    )
                    return@post
                }
                val request = try {
                    moduleJson.decodeFromString(OpenAiChatRequest.serializer(), raw)
                } catch (t: Throwable) {
                    respondApiError(HttpStatusCode.BadRequest, "Invalid JSON body", "invalid_request_error")
                    return@post
                }
                if (request.messages.isEmpty()) {
                    respondApiError(HttpStatusCode.BadRequest, "`messages` must be a non-empty list", "invalid_request_error")
                    return@post
                }
                if (!engine.listRunning().contains(request.model)) {
                    respondApiError(
                        HttpStatusCode.NotFound,
                        "The model `${request.model}` was not found or is not running",
                        "invalid_request_error",
                        "model_not_found",
                    )
                    return@post
                }

                callLog?.model = request.model
                val messages = request.messages.map { ChatMessage(role = it.role, content = it.content) }
                val promptTokens = approximateTokens(messages)
                callLog?.tokensIn = promptTokens
                val params = CompletionParams(
                    temperature = request.temperature?.toFloat() ?: 0.7f,
                    topP = request.top_p?.toFloat() ?: 0.9f,
                    maxTokens = request.max_tokens ?: 512,
                    stream = request.stream,
                    stop = extractStop(request.stop),
                )
                val id = "chatcmpl-${randomId()}"
                val created = System.currentTimeMillis() / 1000L

                if (request.stream) {
                    response.header(HttpHeaders.CacheControl, "no-cache")
                    var chunks = 0
                    try {
                        respondTextWriter(ContentType.Text.EventStream) {
                            try {
                                engine.generate(request.model, messages, params).collect { piece ->
                                    if (piece.isNotEmpty()) {
                                        chunks++
                                        writeSseData(
                                            sseJson.encodeToString(
                                                ChatCompletionChunk.serializer(),
                                                ChatCompletionChunk(
                                                    id = id,
                                                    `object` = "chat.completion.chunk",
                                                    created = created,
                                                    model = request.model,
                                                    choices = listOf(ChunkChoice(delta = DeltaContent(content = piece))),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                writeSseData(
                                    sseJson.encodeToString(
                                        ChatCompletionChunk.serializer(),
                                        ChatCompletionChunk(
                                            id = id,
                                            `object` = "chat.completion.chunk",
                                            created = created,
                                            model = request.model,
                                            choices = listOf(ChunkChoice(delta = DeltaContent(), finish_reason = "stop")),
                                        ),
                                    ),
                                )
                                writeSseData("[DONE]")
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                // Mid-stream failure or client disconnect: close quietly, but try
                                // to surface an error event first (ignored when the pipe is gone).
                                runCatching {
                                    writeSseData(
                                        sseJson.encodeToString(
                                            ApiErrorResponse.serializer(),
                                            ApiErrorResponse(
                                                ApiErrorBody(t.message ?: "Generation failed", "engine_error"),
                                            ),
                                        ),
                                    )
                                    writeSseData("[DONE]")
                                }
                            }
                        }
                    } finally {
                        callLog?.tokensOut = chunks
                    }
                } else {
                    val text = StringBuilder()
                    var chunks = 0
                    try {
                        engine.generate(request.model, messages, params).collect { piece ->
                            text.append(piece)
                            chunks++
                        }
                    } finally {
                        callLog?.tokensOut = chunks
                    }
                    respond(
                        ChatCompletionResponse(
                            id = id,
                            `object` = "chat.completion",
                            created = created,
                            model = request.model,
                            choices = listOf(
                                ChatCompletionChoice(
                                    index = 0,
                                    message = OpenAiMessage(role = "assistant", content = text.toString()),
                                    finish_reason = "stop",
                                ),
                            ),
                            usage = UsageInfo(
                                prompt_tokens = promptTokens,
                                completion_tokens = chunks,
                                total_tokens = promptTokens + chunks,
                            ),
                        ),
                    )
                }
            }
        }

        post("/v1/completions") {
            val callLog = call.attributes.getOrNull(CallLogKey)
            call.withErrorHandling {
                val raw = receiveText()
                if (raw.length > maxBodyBytes) {
                    respondApiError(
                        HttpStatusCode.PayloadTooLarge,
                        "Request body too large (limit is $maxBodyBytes bytes)",
                        "invalid_request_error",
                        "request_too_large",
                    )
                    return@post
                }
                val request = try {
                    moduleJson.decodeFromString(OpenAiCompletionRequest.serializer(), raw)
                } catch (t: Throwable) {
                    respondApiError(HttpStatusCode.BadRequest, "Invalid JSON body", "invalid_request_error")
                    return@post
                }
                val prompt = extractPrompt(request.prompt)
                if (prompt.isEmpty()) {
                    respondApiError(HttpStatusCode.BadRequest, "`prompt` is required", "invalid_request_error")
                    return@post
                }
                if (!engine.listRunning().contains(request.model)) {
                    respondApiError(
                        HttpStatusCode.NotFound,
                        "The model `${request.model}` was not found or is not running",
                        "invalid_request_error",
                        "model_not_found",
                    )
                    return@post
                }

                callLog?.model = request.model
                val messages = listOf(ChatMessage(role = "user", content = prompt))
                val promptTokens = approximateTokens(messages)
                callLog?.tokensIn = promptTokens
                val params = CompletionParams(
                    temperature = request.temperature?.toFloat() ?: 0.7f,
                    topP = request.top_p?.toFloat() ?: 0.9f,
                    maxTokens = request.max_tokens ?: 512,
                    stream = request.stream,
                    stop = extractStop(request.stop),
                )
                val id = "cmpl-${randomId()}"
                val created = System.currentTimeMillis() / 1000L

                if (request.stream) {
                    response.header(HttpHeaders.CacheControl, "no-cache")
                    var chunks = 0
                    try {
                        respondTextWriter(ContentType.Text.EventStream) {
                            try {
                                engine.generate(request.model, messages, params).collect { piece ->
                                    if (piece.isNotEmpty()) {
                                        chunks++
                                        writeSseData(
                                            sseJson.encodeToString(
                                                TextCompletionResponse.serializer(),
                                                TextCompletionResponse(
                                                    id = id,
                                                    `object` = "text_completion",
                                                    created = created,
                                                    model = request.model,
                                                    choices = listOf(TextCompletionChoice(text = piece)),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                writeSseData(
                                    sseJson.encodeToString(
                                        TextCompletionResponse.serializer(),
                                        TextCompletionResponse(
                                            id = id,
                                            `object` = "text_completion",
                                            created = created,
                                            model = request.model,
                                            choices = listOf(TextCompletionChoice(text = "", finish_reason = "stop")),
                                        ),
                                    ),
                                )
                                writeSseData("[DONE]")
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                runCatching {
                                    writeSseData(
                                        sseJson.encodeToString(
                                            ApiErrorResponse.serializer(),
                                            ApiErrorResponse(
                                                ApiErrorBody(t.message ?: "Generation failed", "engine_error"),
                                            ),
                                        ),
                                    )
                                    writeSseData("[DONE]")
                                }
                            }
                        }
                    } finally {
                        callLog?.tokensOut = chunks
                    }
                } else {
                    val text = StringBuilder()
                    var chunks = 0
                    try {
                        engine.generate(request.model, messages, params).collect { piece ->
                            text.append(piece)
                            chunks++
                        }
                    } finally {
                        callLog?.tokensOut = chunks
                    }
                    respond(
                        TextCompletionResponse(
                            id = id,
                            `object` = "text_completion",
                            created = created,
                            model = request.model,
                            choices = listOf(TextCompletionChoice(text = text.toString(), finish_reason = "stop")),
                            usage = UsageInfo(
                                prompt_tokens = promptTokens,
                                completion_tokens = chunks,
                                total_tokens = promptTokens + chunks,
                            ),
                        ),
                    )
                }
            }
        }

        post("/v1/embeddings") {
            call.withErrorHandling {
                respondApiError(
                    HttpStatusCode.NotImplemented,
                    "Embeddings require a runtime with embedding support; not available in this build",
                    "not_supported_error",
                )
            }
        }
    }
}

/** Writes one SSE frame: `data: <payload>\n\n`, flushed immediately. */
private suspend fun WriterScope.writeSseData(payload: String) {
    val bytes = ("data: " + payload + "\n\n").toByteArray(Charsets.UTF_8)
    channel.writeFully(bytes, 0, bytes.size)
    channel.flush()
}

/** Rough prompt-token estimate (spec: message length / 4). */
private fun approximateTokens(messages: List<ChatMessage>): Int =
    (messages.sumOf { it.content.length } / 4).coerceAtLeast(1)

private fun randomId(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val sb = StringBuilder(24)
    repeat(24) { sb.append(alphabet[secureRandom.nextInt(alphabet.length)]) }
    return sb.toString()
}

private fun extractStop(element: JsonElement?): List<String> = when (element) {
    null, is JsonNull -> emptyList()
    is JsonPrimitive -> listOf(element.content)
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
    else -> emptyList()
}

private fun extractPrompt(element: JsonElement?): String = when (element) {
    null, is JsonNull -> ""
    is JsonPrimitive -> element.content
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("\n")
    else -> ""
}
