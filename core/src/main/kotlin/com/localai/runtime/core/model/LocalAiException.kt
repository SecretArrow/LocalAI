package com.localai.runtime.core.model

/**
 * Typed, user-facing error hierarchy. Every operation in the app surfaces one of these
 * instead of raw stack traces. Developer mode may attach [detail].
 */
sealed class LocalAiException(
    message: String,
    val detail: String? = null,
) : Exception(message) {

    class ModelIncompatible(message: String, detail: String? = null) : LocalAiException(message, detail)
    class BackendUnavailable(message: String, detail: String? = null) : LocalAiException(message, detail)
    class InsufficientRam(message: String, val requiredBytes: Long, val availableBytes: Long) : LocalAiException(message)
    class InsufficientStorage(message: String, val requiredBytes: Long, val availableBytes: Long) : LocalAiException(message)
    class DownloadInterrupted(message: String, detail: String? = null) : LocalAiException(message, detail)
    class ChecksumMismatch(message: String, val expected: String, val actual: String) : LocalAiException(message)
    class PortInUse(message: String, val port: Int) : LocalAiException(message)
    class CertificateInvalid(message: String, detail: String? = null) : LocalAiException(message, detail)
    class AuthenticationFailed(message: String) : LocalAiException(message)
    class RuntimeCrashed(message: String, detail: String? = null) : LocalAiException(message, detail)
    class UnsupportedModel(message: String, detail: String? = null) : LocalAiException(message, detail)
    class NotFound(message: String) : LocalAiException(message)
    class Storage(message: String, detail: String? = null) : LocalAiException(message, detail)
    class Network(message: String, detail: String? = null) : LocalAiException(message, detail)
    class Cancelled(message: String = "Operation cancelled") : LocalAiException(message)
}
