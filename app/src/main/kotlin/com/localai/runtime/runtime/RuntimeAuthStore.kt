package com.localai.runtime.runtime

import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.security.SecretStore
import com.localai.runtime.core.settings.SettingsRepository
import com.localai.runtime.core.util.Hashing
import com.localai.runtime.pairing.PairingManager
import com.localai.runtime.server.api.AuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Server-facing authentication: verifies the API token (stored as a SHA-256 hash in the
 * encrypted [SecretStore]) and device tokens issued by the pairing flow (spec §8/§25).
 *
 * The current settings snapshot is cached in memory so [mode] is cheap and non-suspending;
 * the API token hash is read from disk at most once and must be dropped via [invalidateCache]
 * after the token is regenerated.
 */
class RuntimeAuthStore(
    private val settings: SettingsRepository,
    private val secretStore: SecretStore,
    private val pairingManager: PairingManager,
    private val scope: CoroutineScope,
) : AuthStore {

    @Volatile
    private var latest: AppSettings = AppSettings()

    @Volatile
    private var cachedApiTokenHash: String? = null

    @Volatile
    private var cachedHashLoaded = false

    init {
        scope.launch {
            settings.flow.collect { latest = it }
        }
    }

    override fun mode(): String = latest.apiAuthMode.name

    override suspend fun verify(token: String): Boolean {
        if (token.isBlank()) return false
        // Per the frozen AuthStore contract: no token is valid when authentication is
        // disabled — callers must check mode() == NONE and skip verification entirely.
        if (latest.apiAuthMode == AuthMode.NONE) return false

        val presented = SecretStore.sha256(token)
        val stored = currentApiTokenHash()
        if (stored != null && Hashing.constantTimeEquals(presented, stored)) {
            return true
        }
        // Paired devices carry their own dev_ tokens (hash-verified by the pairing manager).
        return pairingManager.verifyToken(token)
    }

    /** Drops cached secrets; call after regenerating the API token so the new one takes effect. */
    fun invalidateCache() {
        cachedApiTokenHash = null
        cachedHashLoaded = false
    }

    private suspend fun currentApiTokenHash(): String? {
        cachedApiTokenHash?.let { return it }
        if (cachedHashLoaded) return null
        val hash = secretStore.tokenSha256(API_TOKEN_NAME)
        cachedApiTokenHash = hash
        cachedHashLoaded = true
        return hash
    }

    private companion object {
        const val API_TOKEN_NAME = "api_token"
    }
}
