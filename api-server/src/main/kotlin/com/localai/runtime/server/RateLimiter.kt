package com.localai.runtime.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.origin.origin
import io.ktor.server.request.path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Token-bucket rate limiter keyed by client IP with two bucket classes:
 * - general requests: 60 per minute (default),
 * - generate endpoints (`/v1/chat/completions`, `/v1/completions`, `/v1/embeddings`): 10 per minute.
 *
 * Buckets live in a [ConcurrentHashMap] and are refilled lazily on access (no background
 * sweeping); idle buckets are pruned opportunistically once the map grows past
 * [CLEANUP_THRESHOLD] entries. Limits are constructor-injectable so tests can use small values.
 */
class RateLimiter(
    private val generalPerMin: Int = 60,
    private val generatePerMin: Int = 10,
) {
    private class Bucket(
        val capacity: Double,
        val refillPerSec: Double,
        var tokens: Double,
        var lastRefillNs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Consumes one token for [clientKey] on [path]; false means the request must be rejected. */
    fun tryAcquire(clientKey: String, path: String): Boolean {
        val now = System.nanoTime()
        if (buckets.size > CLEANUP_THRESHOLD) {
            buckets.entries.removeIf { now - it.value.lastRefillNs > IDLE_TTL_NS }
        }
        val generate = isGeneratePath(path)
        val capacity = (if (generate) generatePerMin else generalPerMin).toDouble()
        val refillPerSec = capacity / 60.0
        val bucket = buckets.computeIfAbsent((if (generate) "g:" else "n:") + clientKey) {
            Bucket(capacity, refillPerSec, capacity, now)
        }
        synchronized(bucket) {
            val elapsedSec = (now - bucket.lastRefillNs) / 1_000_000_000.0
            if (elapsedSec > 0.0) {
                bucket.tokens = min(bucket.capacity, bucket.tokens + elapsedSec * bucket.refillPerSec)
                bucket.lastRefillNs = now
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return true
            }
            return false
        }
    }

    private fun isGeneratePath(path: String): Boolean =
        path.startsWith("/v1/chat/completions") ||
            path.startsWith("/v1/completions") ||
            path.startsWith("/v1/embeddings")

    companion object {
        private const val CLEANUP_THRESHOLD = 1024
        private const val IDLE_TTL_NS = 5L * 60L * 1_000_000_000L
    }
}

/** Installs the per-IP rate limit check; over-limit requests get `429` with a JSON error body. */
internal fun Application.installRateLimit(rateLimiter: RateLimiter) {
    intercept(ApplicationCallPipeline.Setup) {
        val allowed = rateLimiter.tryAcquire(call.request.origin.remoteHost, call.request.path())
        if (allowed) {
            proceed()
        } else {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respondApiError(
                HttpStatusCode.TooManyRequests,
                "Rate limit exceeded",
                "rate_limit_error",
            )
        }
    }
}
