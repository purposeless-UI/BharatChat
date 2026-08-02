package com.droid.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MeshCryptoEngine {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val KEY_LENGTH_BIT = 256

    /**
     * Generates a secure random AES-256 session key for message encryption.
     */
    fun generateSessionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_LENGTH_BIT, SecureRandom())
        return keyGen.generateKey()
    }

    /**
     * Encrypts plaintext message using AES-256-GCM.
     * Returns a combined Base64 string containing IV + Ciphertext.
     */
    fun encrypt(plainText: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        // Combine IV and Ciphertext for transmission over BLE mesh payload
        val combined = iv + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64 encoded AES-256-GCM payload back into plaintext.
     */
    fun decrypt(encryptedBase64: String, secretKey: SecretKey): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        
        // Extract IV and Ciphertext
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTE)
        val cipherText = combined.copyOfRange(IV_LENGTH_BYTE, combined.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        
        val plainTextBytes = cipher.doFinal(cipherText)
        return String(plainTextBytes, Charsets.UTF_8)
    }

    /**
     * Converts a SecretKey to a Base64 string for secure local storage or transmission.
     */
    fun keyToString(secretKey: SecretKey): String {
        return Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Reconstructs a SecretKey from its Base64 string representation.
     */
    fun stringToKey(base64Key: String): SecretKey {
        val decodedBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        return SecretKeySpec(decodedBytes, 0, decodedBytes.size, ALGORITHM)
    }
}