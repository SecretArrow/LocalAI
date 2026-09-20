package com.localai.runtime.pairing

import com.localai.runtime.core.db.LocalAiDatabase
import com.localai.runtime.core.db.PairedDeviceEntity
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.PairedDevice
import com.localai.runtime.core.security.SecretStore
import com.localai.runtime.core.security.TokenGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device pairing (spec §25/§26): a short-lived numeric code is confirmed on this device,
 * after which a long-lived device token is generated and returned exactly once. Only the
 * SHA-256 hash of the token is persisted — the raw token is never stored or displayed
 * again (it can be shown in the UI or embedded in a QR code at approval time only).
 */
class PairingManager(
    private val db: LocalAiDatabase,
    private val secretStore: SecretStore,
    private val scope: CoroutineScope,
) {

    private val pairingMutex = Mutex()

    @Volatile
    private var activeSession: PairingSession? = null

    /** Non-revoked paired devices, without any secret material. */
    val devices: Flow<List<PairedDevice>> = db.pairedDeviceDao().observeAll().map { entities ->
        entities.filter { !it.revoked }.map { it.toDomain() }
    }

    /**
     * Starts (or replaces) the single active pairing session with a fresh six-digit code
     * valid for 60 seconds. The UI renders the code as "xxx-xxx".
     */
    suspend fun beginPairing(): PairingSession = pairingMutex.withLock {
        val session = PairingSession(
            code = TokenGenerator.sixDigitCode(),
            expiresAtMs = System.currentTimeMillis() + SESSION_DURATION_MS,
        )
        activeSession = session
        session
    }

    /**
     * Approves the pairing session and registers the device. Returns the generated device
     * token exactly once — persist or display it now, it is never recoverable later.
     */
    suspend fun approve(session: PairingSession, deviceName: String): String {
        if (session.isExpired()) {
            throw LocalAiException.AuthenticationFailed("Pairing code expired. Start pairing again.")
        }
        val token = TokenGenerator.randomToken("dev_")
        val now = System.currentTimeMillis()
        db.pairedDeviceDao().upsert(
            PairedDeviceEntity(
                id = "dev-$now",
                name = deviceName.trim().ifBlank { "Paired device" },
                tokenHash = SecretStore.sha256(token),
                tokenPrefix = token.take(8),
                createdAt = now,
                lastSeenAt = now,
                revoked = false,
            ),
        )
        activeSession = null
        return token
    }

    suspend fun revoke(id: String) {
        val entity = entityById(id) ?: return
        db.pairedDeviceDao().upsert(entity.copy(revoked = true))
    }

    suspend fun rename(id: String, name: String) {
        val entity = entityById(id) ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        db.pairedDeviceDao().upsert(entity.copy(name = clean))
    }

    suspend fun touchLastSeen(tokenHash: String) {
        val entity = db.pairedDeviceDao().byTokenHash(tokenHash) ?: return
        db.pairedDeviceDao().updateLastSeen(entity.id, System.currentTimeMillis())
    }

    /** True when the token's hash matches a non-revoked paired device. */
    suspend fun verifyToken(token: String): Boolean {
        if (token.isBlank()) return false
        val entity = db.pairedDeviceDao().byTokenHash(SecretStore.sha256(token)) ?: return false
        if (entity.revoked) return false
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                db.pairedDeviceDao().updateLastSeen(entity.id, now)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Fire-and-forget bookkeeping: last-seen updates must not fail verification.
            }
        }
        return true
    }

    private suspend fun entityById(id: String): PairedDeviceEntity? =
        db.pairedDeviceDao().observeAll().first().firstOrNull { it.id == id }

    private fun PairedDeviceEntity.toDomain() = PairedDevice(
        id = id,
        name = name,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        revoked = revoked,
    )

    private companion object {
        const val SESSION_DURATION_MS = 60_000L
    }
}

/** An in-progress pairing handshake. [code] is digits-only; format for display as "xxx-xxx". */
data class PairingSession(
    val code: String,
    val expiresAtMs: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtMs
}
