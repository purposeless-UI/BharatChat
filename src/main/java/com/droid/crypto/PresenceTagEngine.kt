package com.droid.crypto

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PresenceTagEngine {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Computes a rotating daily presence tag for a given contact secret using UTC time window.
     * This allows paired peers to recognize each other during a Bluetooth scan 
     * without exposing static keys or identifiers to strangers.
     */
    fun generateDailyPresenceTag(contactSharedSecret: ByteArray): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateString = dateFormat.format(Date())
        val timeContext = dateString.toByteArray(Charsets.UTF_8)

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(contactSharedSecret, HMAC_ALGORITHM)
        mac.init(keySpec)
        
        val hmacBytes = mac.doFinal(timeContext)
        
        // Truncate to first 16 bytes for compact BLE broadcast payload
        val truncated = hmacBytes.copyOfRange(0, 16)
        return Base64.encodeToString(truncated, Base64.NO_WRAP)
    }

    /**
     * Verifies if a scanned tag matches any known contact by evaluating 
     * their shared secrets against the current UTC day window.
     */
    fun matchesAnyContact(scannedTag: String, knownContactSecrets: List<ByteArray>): Boolean {
        for (secret in knownContactSecrets) {
            val expectedTag = generateDailyPresenceTag(secret)
            if (expectedTag == scannedTag) {
                return true
            }
        }
        return false
    }
}