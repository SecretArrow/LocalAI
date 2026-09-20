package com.localai.runtime.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.localai.runtime.core.download.NetworkGate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * ConnectivityManager-backed [NetworkGate] plus a live online-state flow.
 *
 * [online] is a cold callbackFlow: a [ConnectivityManager.NetworkCallback] is
 * registered when the flow is collected ("started on collect") and unregistered
 * on cancellation. Every collection immediately receives the current online
 * state first (replay-1 semantics without needing a shared scope), so a fresh
 * subscriber never has to wait for a network event.
 */
class NetworkMonitor(private val context: Context) : NetworkGate {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager?
        get() = try {
            appContext.getSystemService(ConnectivityManager::class.java)
        } catch (_: Throwable) {
            null
        }

    /** Emits `true`/`false` as connectivity changes; starts with the current state. */
    val online: Flow<Boolean> = callbackFlow {
        val connectivity = connectivityManager
        if (connectivity == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(currentlyOnline())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(currentlyOnline())
            }
        }
        trySend(currentlyOnline())
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        } catch (_: Throwable) {
            trySend(currentlyOnline())
        }
        awaitClose {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
                // already unregistered
            }
        }
    }

    override fun isOnline(): Boolean = currentlyOnline()

    override fun isWifi(): Boolean {
        val connectivity = connectivityManager ?: return false
        return try {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            false
        }
    }

    override fun isMetered(): Boolean = try {
        connectivityManager?.isActiveNetworkMetered ?: true
    } catch (_: Throwable) {
        true
    }

    private fun currentlyOnline(): Boolean {
        val connectivity = connectivityManager ?: return false
        return try {
            val active = connectivity.activeNetwork ?: return false
            connectivity.getNetworkCapabilities(active)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Throwable) {
            false
        }
    }
}
