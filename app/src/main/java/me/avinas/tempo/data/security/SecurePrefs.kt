package me.avinas.tempo.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SharedPreferences] whose *values* are AES-256/GCM sealed with a key that never leaves the
 * Android Keystore.
 *
 * androidx.security:security-crypto (EncryptedSharedPreferences/MasterKey) is deprecated with no
 * replacement artifact — the migration advice is to use the platform APIs directly, which is what
 * this does. Preference *names* stay in the clear so `contains`/`remove` work against the raw XML;
 * every value written through this wrapper is ciphertext (IV || ciphertext, Base64). Stored
 * non-String values (e.g. Long timestamps) are written as sealed strings too, which is why
 * `getLong` reads them back from [SharedPreferences.getString]. Only String and Long values are
 * sealed — any other preference type would fall through to the delegate in the clear, so extend
 * this class before storing one.
 *
 * Keystore failures (corrupt/locked OEM keystores, wiped keys after a restore) must never take the
 * app down: the key is resolved lazily and a failure degrades to "nothing stored" — reads return
 * the caller's default, writes are dropped. There is no plaintext fallback.
 */
class SecurePrefs private constructor(
    private val delegate: SharedPreferences,
    private val keyAlias: String,
) : SharedPreferences by delegate {

    private val cachedKey: SecretKey? by lazy {
        try {
            loadOrCreateKey(keyAlias)
        } catch (e: Exception) {
            Log.e(TAG, "Keystore key '$keyAlias' unavailable; secure prefs will read as empty", e)
            null
        }
    }

    override fun getString(key: String, defValue: String?): String? =
        delegate.getString(key, null)?.let { decrypt(it) } ?: defValue

    /** Values are sealed strings, so a stored Long is read as `String` and parsed back. */
    override fun getLong(key: String, defValue: Long): Long =
        delegate.getString(key, null)?.let { decrypt(it) }?.toLongOrNull() ?: defValue

    override fun edit(): SharedPreferences.Editor = SecureEditor(delegate.edit())

    private inner class SecureEditor(
        private val editor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by editor {

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) {
                editor.remove(key)
                return this
            }
            // A failed seal drops the write rather than ever persisting plaintext.
            encrypt(value)?.let { editor.putString(key, it) }
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            encrypt(value.toString())?.let { editor.putString(key, it) }
            return this
        }
    }

    private fun encrypt(plain: String): String? {
        val secretKey = cachedKey ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val sealed = ByteArray(iv.size + body.size)
            iv.copyInto(sealed, 0)
            body.copyInto(sealed, iv.size)
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt a preference value", e)
            null
        }
    }

    private fun decrypt(stored: String): String? {
        val secretKey = cachedKey ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
            )
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping an undecryptable preference value", e)
            null
        }
    }

    companion object {
        private const val TAG = "SecurePrefs"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Keystore aliases created by the deprecated androidx.security:security-crypto library. */
        private const val LEGACY_KEY_PREFIX = "__androidx_security_crypto"
        private val LEGACY_KEY_ALIASES = listOf(
            "_androidx_security_crypto_encryption_master_key",
            "_androidx_security_crypto_encryption_master_key_256",
        )

        /**
         * Opens (and creates on first use) the secure store. Each store should own one alias for
         * the lifetime of the app, so a key is generated once per installation.
         */
        fun open(context: Context, name: String, keyAlias: String): SharedPreferences {
            val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            wipeLegacyStore(raw)
            return SecurePrefs(raw, keyAlias)
        }

        /**
         * One-time migration off EncryptedSharedPreferences 1.1.0: its file stores an encrypted
         * keyset plus encrypted key names, so nothing in it is readable as plain preferences.
         * Anything left over is dropped and the now-unused Keystore aliases are deleted. Users
         * sign in to Drive/Spotify again once — those tokens are re-obtainable credentials, which
         * is a better trade than keeping a deprecated dependency alive for a one-shot migration.
         */
        private fun wipeLegacyStore(raw: SharedPreferences) {
            if (raw.all.keys.none { it.startsWith(LEGACY_KEY_PREFIX) }) return
            Log.i(TAG, "Discarding preferences written by the deprecated security-crypto library")
            raw.edit().clear().commit()
            deleteLegacyKeys()
        }

        private fun deleteLegacyKeys() {
            try {
                val keyStore = keyStore()
                for (alias in LEGACY_KEY_ALIASES) {
                    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete legacy Keystore keys", e)
            }
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        private fun loadOrCreateKey(alias: String): SecretKey {
            val keyStore = keyStore()
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
