package com.localai.runtime.server

import com.localai.runtime.server.api.AuthStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.origin.origin
import io.ktor.server.request.*
import io.ktor.server.response.header

/**
 * Request authentication for the API server.
 *
 * Modes (read per-request from [AuthStore.mode] so live config changes apply immediately):
 * - `NONE`     — no token required.
 * - `BEARER`   — `Authorization: Bearer <token>` only.
 * - `API_KEY`  — accepts `Authorization: Bearer <token>` OR `X-API-Key: <token>`.
 *
 * `GET /api/health`, `GET /` (web console) and `GET /favicon.ico` always skip authentication.
 * Rejected requests get `401` with an OpenAI-style error body.
 */
internal fun Application.installAuth(auth: AuthStore) {
    intercept(ApplicationCallPipeline.Setup) {
        val path = call.request.path()
        if (call.request.httpMethod == HttpMethod.Get && path in AUTH_EXEMPT_PATHS) {
            proceed()
            return@intercept
        }
        val mode = auth.mode().trim().uppercase()
        if (mode == "NONE") {
            proceed()
            return@intercept
        }
        val token = extractToken(call, mode)
        if (token != null && auth.verify(token)) {
            proceed()
        } else {
            if (mode == "BEARER") {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"localai\"")
            }
            call.respondApiError(
                HttpStatusCode.Unauthorized,
                "Invalid or missing API token",
                "authentication_error",
                "invalid_api_key",
            )
        }
    }
}

private val AUTH_EXEMPT_PATHS = setOf("/api/health", "/", "/favicon.ico")

private fun extractToken(call: ApplicationCall, mode: String): String? {
    val authorization = call.request.headers[HttpHeaders.Authorization]
    if (authorization != null && authorization.length > 7) {
        val prefix = authorization.substring(0, 7)
        if (prefix.equals("Bearer ", ignoreCase = true)) {
            val token = authorization.substring(7).trim()
            if (token.isNotEmpty()) return token
        }
    }
    if (mode == "API_KEY") {
        val apiKey = call.request.headers["X-API-Key"]?.trim()
        if (!apiKey.isNullOrEmpty()) return apiKey
    }
    return null
}
