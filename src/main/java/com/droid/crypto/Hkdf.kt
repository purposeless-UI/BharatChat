package com.droid.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveKey(inputKeyMaterial: ByteArray, info: String): ByteArray {
        val salt = ByteArray(32)
        val prk = hmacSha256(salt, inputKeyMaterial)
        return hmacSha256(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }
}