package com.localai.runtime.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises the local API server on the LAN via NSD/mDNS as "_localai._tcp.".
 * Registration is best-effort: any failure is swallowed and [isRunning] stays false —
 * discovery is a convenience, never a hard dependency (spec §26).
 */
class LanAdvertiser(private val context: Context) {

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registered = false

    /** True while a service registration is active. */
    val isRunning: Boolean
        get() = registered

    /** Registers the service; no-op when already running or when NSD is unavailable. */
    fun start(port: Int, name: String = DEFAULT_NAME) {
        if (registered) return
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                listener = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Nothing to do: the listener is discarded either way.
            }
        }
        listener = registrationListener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (t: Throwable) {
            listener = null
            registered = false
        }
    }

    /** Unregisters the service; safe to call multiple times or when never started. */
    fun stop() {
        val current = listener ?: return
        listener = null
        registered = false
        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager.unregisterService(current)
        } catch (t: Throwable) {
            // Already unregistered or NSD unavailable — nothing to do.
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_localai._tcp."
        const val DEFAULT_NAME = "LocalAI"
    }
}
