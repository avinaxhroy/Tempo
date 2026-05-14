package me.avinas.tempo.desktop

import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH key exchange for secure pairing.
 * Uses P-256 curve to derive a shared secret without transmitting the auth token in cleartext.
 *
 * Protocol:
 * 1. Desktop generates ECDH key pair, includes public key in QR
 * 2. Phone scans QR, generates its own ECDH key pair
 * 3. Both sides compute shared_secret = ECDH(local_private, remote_public)
 * 4. Both derive auth_token = HKDF-SHA256(shared_secret, "tempo-sync-auth-v3")
 * 5. Phone sends its public key to desktop via /api/pair/confirm (HMAC-signed with derived token)
 * 6. Desktop verifies and completes pairing
 *
 * Key format: SEC1 uncompressed point (0x04 || X || Y, 65 bytes for P-256), base64url-no-pad.
 * This matches the Rust/desktop p256 crate's to_encoded_point(false) output.
 */
object EcdhKeyExchange {
    private const val TAG = "EcdhKeyExchange"
    private const val CURVE = "EC"
    private const val CURVE_NAME = "prime256v1"
    private val HKDF_INFO = "tempo-sync-auth-v3".toByteArray(Charsets.UTF_8)
    private val HKDF_SALT = "tempo-ecdh-salt-v1".toByteArray(Charsets.UTF_8)
    private const val TOKEN_LENGTH = 32
    private const val SEC1_UNCOMPRESSED_PREFIX: Byte = 0x04

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(CURVE)
        generator.initialize(ECGenParameterSpec(CURVE_NAME))
        return generator.generateKeyPair()
    }

    /**
     * Encode a public key as SEC1 uncompressed point (0x04 || X || Y) in base64url-no-pad.
     * This matches the desktop Rust side's `to_encoded_point(false)` output.
     */
    fun publicKeyToBase64(publicKey: PublicKey): String {
        val ecKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected ECPublicKey, got ${publicKey.algorithm}")
        val w = ecKey.w
        val xBytes = w.affineX.toByteArray().stripLeadingZeroes()
        val yBytes = w.affineY.toByteArray().stripLeadingZeroes()
        val xPadded = xBytes.padToCoordinateSize()
        val yPadded = yBytes.padToCoordinateSize()
        val sec1 = ByteArray(1 + xPadded.size + yPadded.size)
        sec1[0] = SEC1_UNCOMPRESSED_PREFIX
        System.arraycopy(xPadded, 0, sec1, 1, xPadded.size)
        System.arraycopy(yPadded, 0, sec1, 1 + xPadded.size, yPadded.size)
        return Base64.encodeToString(sec1, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /**
     * Decode a base64url-encoded SEC1 uncompressed point (or X.509 SubjectPublicKeyInfo)
     * into a PublicKey. Tries SEC1 first (interoperability with desktop), then falls back to X.509.
     */
    fun base64ToPublicKey(base64: String): PublicKey? {
        return try {
            val bytes = decodeBase64Flexible(base64)
            if (bytes.isNotEmpty() && bytes[0] == SEC1_UNCOMPRESSED_PREFIX) {
                // SEC1 uncompressed point format — reconstruct X.509 SubjectPublicKeyInfo
                val ecSpec = ECGenParameterSpec(CURVE_NAME)
                val keyFactory = KeyFactory.getInstance(CURVE)
                val params = java.security.AlgorithmParameters.getInstance(CURVE)
                params.init(ecSpec)
                val ecParams = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
                val w = java.security.spec.ECPoint(
                    java.math.BigInteger(1, bytes.copyOfRange(1, 1 + 32)),
                    java.math.BigInteger(1, bytes.copyOfRange(1 + 32, 1 + 64))
                )
                val pubKeySpec = java.security.spec.ECPublicKeySpec(w, ecParams)
                keyFactory.generatePublic(pubKeySpec)
            } else {
                // X.509 SubjectPublicKeyInfo format
                val spec = X509EncodedKeySpec(bytes)
                KeyFactory.getInstance(CURVE).generatePublic(spec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode public key", e)
            null
        }
    }

    fun privateKeyToBase64(privateKey: PrivateKey): String {
        return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun base64ToPrivateKey(base64: String): PrivateKey? {
        return try {
            val bytes = decodeBase64Flexible(base64)
            val spec = PKCS8EncodedKeySpec(bytes)
            KeyFactory.getInstance(CURVE).generatePrivate(spec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode private key", e)
            null
        }
    }

    fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    fun deriveAuthToken(sharedSecret: ByteArray): String {
        val prk = hkdfExtract(sharedSecret)
        val okm = hkdfExpand(prk, HKDF_INFO, TOKEN_LENGTH)
        return okm.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hkdfExtract(ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var offset = 0
        var t = ByteArray(0)
        var counter: Byte = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter = (counter.toInt() + 1).toByte()
        }
        return result
    }

    fun rotateAuthToken(currentToken: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(currentToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("tempo-token-rotation-v1".toByteArray(Charsets.UTF_8))
        val rotated = mac.doFinal()
        return rotated.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Decode base64 with or without URL-safe padding.
     * Handles both padded (= suffixes) and no-pad variants.
     */
    private fun decodeBase64Flexible(base64: String): ByteArray {
        val noPad = base64.trimEnd('=')
            .replace('+', '-')
            .replace('_', '/')
        val paddingNeeded = (4 - noPad.length % 4) % 4
        val padded = noPad + "=".repeat(paddingNeeded)
        return Base64.decode(padded, Base64.NO_WRAP)
    }

    /**
     * Padded coordinate bytes to exactly 32 bytes (P-256 field size).
     */
    private fun ByteArray.padToCoordinateSize(): ByteArray {
        if (this.size == 32) return this
        if (this.size > 32) return this.copyOfRange(this.size - 32, this.size)
        val padded = ByteArray(32)
        System.arraycopy(this, 0, padded, 32 - this.size, this.size)
        return padded
    }

    private fun ByteArray.stripLeadingZeroes(): ByteArray {
        var start = 0
        while (start < this.size - 1 && this[start] == 0.toByte()) start++
        return this.copyOfRange(start, this.size)
    }
}