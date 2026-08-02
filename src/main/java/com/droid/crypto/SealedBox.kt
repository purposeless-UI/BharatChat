package com.droid.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SealedBox {
    data class Sealed(
        val ephemeralPubkeyHex: String,
        val ivHex: String,
        val ciphertextHex: String
    )

    fun seal(recipientCompressedPubkey: ByteArray, plaintext: ByteArray): Sealed {
        require(recipientCompressedPubkey.size == 33) {
            "Recipient public key must be 33 bytes (compressed format), got ${recipientCompressedPubkey.size}"
        }
        val ephemeralSecret = Secp256k1Signer.randomSecretKey()
        val ephemeralPubkey = Secp256k1Signer.pubkeyCreate(ephemeralSecret)
        val shared = Secp256k1Signer.ecdh(ephemeralSecret, recipientCompressedPubkey)
        val key = Hkdf.deriveKey(shared, "bharatchat-sealed-box")

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return Sealed(
            ephemeralPubkeyHex = ephemeralPubkey.toHex(),
            ivHex = iv.toHex(),
            ciphertextHex = ciphertext.toHex()
        )
    }

    fun unseal(mySecretKey: ByteArray, sealed: Sealed): ByteArray {
        require(mySecretKey.size == 32) {
            "Secret key must be 32 bytes, got ${mySecretKey.size}"
        }
        val shared = Secp256k1Signer.ecdh(mySecretKey, sealed.ephemeralPubkeyHex.hexToBytes())
        val key = Hkdf.deriveKey(shared, "bharatchat-sealed-box")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, sealed.ivHex.hexToBytes())
        )
        return cipher.doFinal(sealed.ciphertextHex.hexToBytes())
    }
}