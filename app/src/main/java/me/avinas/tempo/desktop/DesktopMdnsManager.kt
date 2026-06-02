package me.avinas.tempo.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopMdnsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pairingManager: DesktopPairingManager
) {
    companion object {
        private const val TAG = "DesktopMdnsManager"
        private const val SERVICE_TYPE = "_tempo._tcp."
        private const val SERVICE_NAME = "Tempo-Phone"
        private const val RE_REGISTER_INTERVAL_MS = 120_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reRegisterJob: Job? = null

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

    fun register(port: Int = DesktopPairingManager.SERVER_PORT) {
        if (isRegistered) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (_: Exception) { /* wasn't registered */ }
            isRegistered = false
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)

            val phoneIp = pairingManager.getLocalIpAddress()
            if (phoneIp.isNotBlank() && phoneIp != "0.0.0.0") {
                setAttribute("ip", phoneIp)
            }
            setAttribute("device", Build.MODEL)
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.i(TAG, "Registering mDNS service on port $port with IP ${pairingManager.getLocalIpAddress()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }

        startPeriodicReRegistration(port)
    }

    fun unregister() {
        reRegisterJob?.cancel()
        reRegisterJob = null

        if (!isRegistered) return

        try {
            nsdManager.unregisterService(registrationListener)
            Log.i(TAG, "Unregistering mDNS service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister mDNS service", e)
        }
    }

    private fun startPeriodicReRegistration(port: Int) {
        reRegisterJob?.cancel()
        reRegisterJob = scope.launch {
            while (true) {
                delay(RE_REGISTER_INTERVAL_MS)
                if (isRegistered) {
                    Log.d(TAG, "Periodic mDNS re-registration")
                    register(port)
                }
            }
        }
    }
}