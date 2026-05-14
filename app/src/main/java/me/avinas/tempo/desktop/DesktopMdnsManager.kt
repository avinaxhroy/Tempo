package me.avinas.tempo.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
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

    fun register(port: Int = DesktopPairingManager.SERVER_PORT) {
        if (isRegistered) {
            // Must unregister first so TXT records (tsha) update on re-registration
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (_: Exception) { /* wasn't registered */ }
            isRegistered = false
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            val session = try {
                kotlinx.coroutines.runBlocking { pairingManager.getActiveSession() }
            } catch (_: Exception) { null }
            session?.let {
                val decrypted = TokenEncryptor.decrypt(it.authToken) ?: it.authToken
                val hash = sha256Truncate(decrypted, 8)
                setAttribute("tsha", hash)
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.i(TAG, "Registering mDNS service on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }
    }

    fun unregister() {
        if (!isRegistered) return

        try {
            nsdManager.unregisterService(registrationListener)
            Log.i(TAG, "Unregistering mDNS service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister mDNS service", e)
        }
    }

    private fun sha256Truncate(input: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}