package me.avinas.tempo.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and manages an mDNS/DNS-SD service so the desktop app can auto-discover
 * this phone on the local network when DHCP IPs change.
 *
 * Service type: `_tempo._tcp.` (matches desktop's mDNS browse query)
 * Port: [DesktopPairingManager.SERVER_PORT]
 */
@Singleton
class DesktopMdnsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DesktopMdnsManager"
        private const val SERVICE_TYPE = "_tempo._tcp."
        private const val SERVICE_NAME = "Tempo-Phone"
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    @Volatile
    private var isRegistered = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "mDNS service registered: ${serviceInfo.serviceName}")
            isRegistered = true
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS registration failed (error=$errorCode)")
            isRegistered = false
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "mDNS service unregistered")
            isRegistered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS unregistration failed (error=$errorCode)")
        }
    }

    /**
     * Register the Tempo receiver mDNS service at the given port.
     * Call when the satellite HTTP server starts.
     */
    fun register(port: Int = DesktopPairingManager.SERVER_PORT) {
        if (isRegistered) {
            Log.d(TAG, "mDNS service already registered, skipping")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.i(TAG, "Registering mDNS service on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }
    }

    /**
     * Unregister the mDNS service.
     * Call when the satellite HTTP server stops or pairing is disconnected.
     */
    fun unregister() {
        if (!isRegistered) return

        try {
            nsdManager.unregisterService(registrationListener)
            Log.i(TAG, "Unregistering mDNS service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister mDNS service", e)
        }
    }
}
