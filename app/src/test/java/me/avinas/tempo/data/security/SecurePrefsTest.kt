package me.avinas.tempo.data.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory SharedPreferences double. The JVM has no AndroidKeyStore, so these tests exercise the
 * two paths that do not need real crypto: the one-shot legacy wipe and the promise that a keystore
 * failure degrades to "nothing stored" instead of throwing or persisting plaintext.
 */
private class InMemoryPrefsContext : ContextWrapper(null) {
    val values = mutableMapOf<String, Any>()

    private val editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
            "remove" -> { values.remove(args!![0] as String); proxy }
            "clear" -> { values.clear(); proxy }
            "commit" -> true
            "apply" -> null
            else -> error("Unexpected editor call: ${method.name}")
        }
    } as SharedPreferences.Editor

    val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0] as String]
            "contains" -> values.containsKey(args!![0] as String)
            "getAll" -> values.toMap()
            "edit" -> editor
            else -> error("Unexpected preference call: ${method.name}")
        }
    } as SharedPreferences

    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

class SecurePrefsTest {

    @Test
    fun `prefs written by the old security-crypto library are discarded once`() {
        val context = InMemoryPrefsContext()
        context.values["__androidx_security_crypto_encrypted_prefs_key_keyset__"] = "keyset"
        context.values["AR+0Gm/A+L1Q=="] = "ciphertext"

        SecurePrefs.open(context, "spotify_auth_prefs", "test_alias")

        assertTrue("legacy store should be wiped, got ${context.values}", context.values.isEmpty())
    }

    @Test
    fun `an unusable keystore degrades to empty instead of plaintext`() {
        val context = InMemoryPrefsContext()
        val prefs = SecurePrefs.open(context, "spotify_auth_prefs", "test_alias")

        prefs.edit().putString("access_token", "live-token").putLong("expires_at", 42L).apply()

        assertNull(prefs.getString("access_token", null))
        assertEquals(-1L, prefs.getLong("expires_at", -1L))
        assertTrue(
            "no value may be stored in the clear, got ${context.values}",
            context.values.values.none { it.toString().contains("live-token") || it.toString() == "42" },
        )
    }
}
