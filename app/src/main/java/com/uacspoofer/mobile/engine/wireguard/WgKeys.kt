package com.uacspoofer.mobile.engine.wireguard

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import com.uacspoofer.mobile.profiles.Base64Codec

/** Curve25519 keys for WireGuard, on the BouncyCastle copy Simorgh already ships. */
object WgKeys {
    private val random = SecureRandom()

    data class KeyPair(val privateKey: String, val publicKey: String)

    fun generate(): KeyPair {
        val priv = ByteArray(32)
        X25519.generatePrivateKey(random, priv)
        return KeyPair(b64(priv), publicOf(priv))
    }

    fun publicKey(privateKeyB64: String): String = publicOf(decode32(privateKeyB64))

    /**
     * The X25519 twin of an Ed25519 seed: SHA-512(seed)[0..32], clamped. This is how Proton's
     * servers derive the WireGuard key from the Ed25519 key registered for a certificate.
     */
    fun x25519FromEd25519Seed(seed: ByteArray): String {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
        return b64(h)
    }

    fun isKey(value: String): Boolean = runCatching { decode32(value) }.isSuccess

    fun requireKey(value: String, what: String): String {
        val v = value.trim()
        if (!isKey(v)) throw WgParseException("$what نامعتبر است (باید ۳۲ بایت base64 باشد)")
        return v
    }

    private fun publicOf(priv: ByteArray): String {
        val pub = ByteArray(32)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return b64(pub)
    }

    private fun decode32(v: String): ByteArray {
        val bytes = Base64Codec.decode(v.trim())
        require(bytes.size == 32) { "not 32 bytes" }
        return bytes
    }

    private fun b64(b: ByteArray): String = Base64Codec.encode(b)
}
