package com.droid.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

@Suppress("unused", "SpellCheckingInspection")
object Secp256k1Signer {
    private const val SECRET_KEY_SIZE = 32
    private const val COMPRESSED_PUBKEY_SIZE = 33
    private const val UNCOMPRESSED_PUBKEY_SIZE = 65
    private const val SCHNORR_SIGNATURE_SIZE = 64
    private const val X_ONLY_PUBKEY_SIZE = 32

    private val random = SecureRandom()

    fun randomSecretKey(): ByteArray {
        val sk = ByteArray(SECRET_KEY_SIZE)
        random.nextBytes(sk)
        return sk
    }

    fun pubkeyCreate(secretKey: ByteArray): ByteArray {
        require(secretKey.size == SECRET_KEY_SIZE) {
            "Secret key must be 32 bytes, got ${secretKey.size}"
        }
        return Secp256k1.pubkeyCreate(secretKey)
    }

    fun xOnlyPubkey(secretKey: ByteArray): ByteArray {
        val full = pubkeyCreate(secretKey)
        return full.copyOfRange(1, COMPRESSED_PUBKEY_SIZE)
    }

    fun ecdh(secretKey: ByteArray, compressedPublicKey: ByteArray): ByteArray {
        require(secretKey.size == SECRET_KEY_SIZE) {
            "Secret key must be 32 bytes, got ${secretKey.size}"
        }
        val pubkey = normalizePublicKey(compressedPublicKey)
        return Secp256k1.ecdh(secretKey, pubkey)
    }

    /**
     * Converts any public key (compressed 33‑byte or uncompressed 65‑byte) to
     * a compressed 33‑byte public key.
     * For uncompressed keys (0x04), the compressed form is:
     * - prefix 0x02 if y is even, 0x03 if y is odd
     * - followed by the 32‑byte x‑coordinate
     */
    fun normalizePublicKey(publicKey: ByteArray): ByteArray {
        return when (publicKey.size) {
            COMPRESSED_PUBKEY_SIZE -> publicKey
            UNCOMPRESSED_PUBKEY_SIZE -> {
                require(publicKey[0] == 0x04.toByte()) {
                    "Invalid uncompressed key prefix: expected 0x04"
                }
                val x = publicKey.copyOfRange(1, 33)
                val yLastByte = publicKey[64].toInt() and 0xFF
                val prefix = if (yLastByte % 2 == 0) 0x02 else 0x03
                byteArrayOf(prefix.toByte()) + x
            }
            else -> throw IllegalArgumentException(
                "Invalid public key length: ${publicKey.size}. Expected 33 (compressed) or 65 (uncompressed)."
            )
        }
    }

    /**
     * Convenience function to normalize a public key from a hex string.
     * Accepts 66‑hex (compressed or uncompressed) and returns compressed 66‑hex.
     */
    fun normalizePublicKeyHex(hex: String): String {
        val bytes = hex.hexToBytes()
        return normalizePublicKey(bytes).toHex()
    }

    fun signSchnorr(messageHash32: ByteArray, secretKey: ByteArray): ByteArray {
        require(messageHash32.size == X_ONLY_PUBKEY_SIZE) {
            "Message hash must be 32 bytes, got ${messageHash32.size}"
        }
        require(secretKey.size == SECRET_KEY_SIZE) {
            "Secret key must be 32 bytes, got ${secretKey.size}"
        }
        val auxRand = ByteArray(X_ONLY_PUBKEY_SIZE).also { random.nextBytes(it) }
        return Secp256k1.signSchnorr(messageHash32, secretKey, auxRand)
    }

    fun verifySchnorr(signature: ByteArray, messageHash32: ByteArray, xOnlyPubkey: ByteArray): Boolean {
        require(signature.size == SCHNORR_SIGNATURE_SIZE) {
            "Signature must be 64 bytes, got ${signature.size}"
        }
        require(messageHash32.size == X_ONLY_PUBKEY_SIZE) {
            "Message hash must be 32 bytes, got ${messageHash32.size}"
        }
        require(xOnlyPubkey.size == X_ONLY_PUBKEY_SIZE) {
            "X‑only public key must be 32 bytes, got ${xOnlyPubkey.size}"
        }
        return Secp256k1.verifySchnorr(signature, messageHash32, xOnlyPubkey)
    }
}