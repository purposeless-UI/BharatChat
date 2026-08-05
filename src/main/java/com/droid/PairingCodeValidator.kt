package com.droid

import android.util.Log

/**
 * Utility for extracting and validating BharatChat pairing codes.
 */
@Suppress("SpellCheckingInspection")
object PairingCodeValidator {
    private const val TAG = "PairingCodeValidator"

    /**
     * Extracts a compressed (66-hex) public key from various input formats.
     * @param input Raw input (may contain scheme prefixes, URLs, etc.).
     * @return Compressed public key (66 hex) or null if none found.
     */
    fun extractPublicKey(input: String): String? {
        var content = input.trim()
        Log.d(TAG, "extractPublicKey: input='$input'")

        // Strip any known scheme
        val schemePattern = Regex("""^[a-zA-Z]+://""")
        if (schemePattern.containsMatchIn(content)) {
            content = schemePattern.replaceFirst(content, "")
            Log.d(TAG, "After stripping scheme: '$content'")
        }
        if (content.startsWith("bharatchat:", ignoreCase = true)) {
            content = content.substring("bharatchat:".length).trim()
            Log.d(TAG, "After stripping 'bharatchat:': '$content'")
        }

        var extractedKey: String? = null

        // 1. Look for 130-hex (uncompressed) – full key
        val hex130 = Regex("""[0-9a-fA-F]{130}""").find(content)?.value
        if (hex130 != null) {
            val lower = hex130.lowercase()
            if (lower.startsWith("04")) {
                extractedKey = lower
                Log.d(TAG, "Found 130-hex uncompressed key: '$extractedKey'")
            } else {
                Log.w(TAG, "Found 130-hex but invalid prefix: $lower")
                return null
            }
        } else {
            // 2. Look for 66-hex (compressed)
            val hex66 = Regex("""[0-9a-fA-F]{66}""").find(content)?.value
            if (hex66 != null) {
                val lower = hex66.lowercase()
                if (lower.startsWith("02") || lower.startsWith("03")) {
                    extractedKey = lower
                    Log.d(TAG, "Found 66-hex compressed key: '$extractedKey'")
                } else if (lower.startsWith("04")) {
                    Log.w(TAG, "Found 66-hex with 04 prefix – invalid truncated key: $lower")
                    return null
                } else {
                    Log.w(TAG, "Found 66-hex but invalid prefix: $lower")
                    return null
                }
            }
        }

        // 3. Reject 64-hex (x-only)
        if (extractedKey == null) {
            val hex64 = Regex("""[0-9a-fA-F]{64}""").find(content)?.value
            if (hex64 != null) {
                Log.w(TAG, "Found 64-hex (x-only): $hex64")
                return null
            }
        }

        // 4. Try IdentityStore if it starts with bharatchat://
        if (extractedKey == null && input.startsWith("bharatchat://", ignoreCase = true)) {
            try {
                extractedKey = IdentityStore.pubkeyFromPairingCode(input)
                Log.d(TAG, "IdentityStore extracted: '$extractedKey'")
            } catch (e: Exception) {
                Log.e(TAG, "IdentityStore extraction failed", e)
            }
        }

        return extractedKey
    }
}