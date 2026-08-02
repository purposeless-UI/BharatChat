package com.droid

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.hexToBytes
import com.droid.crypto.toHex
import java.util.Arrays

@Suppress("DataClassArrayMember", "SpellCheckingInspection")
data class Identity(
    val secretKey: ByteArray,
    val compressedPublicKeyHex: String
) {
    val xOnlyPublicKeyHex: String
        get() = if (compressedPublicKeyHex.length == 66) compressedPublicKeyHex.drop(2) else compressedPublicKeyHex

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Identity
        if (!Arrays.equals(secretKey, other.secretKey)) return false
        if (compressedPublicKeyHex != other.compressedPublicKeyHex) return false
        return true
    }

    override fun hashCode(): Int {
        var result = Arrays.hashCode(secretKey)
        result = 31 * result + compressedPublicKeyHex.hashCode()
        return result
    }
}

@Suppress("SpellCheckingInspection", "KotlinExtension")
object IdentityStore {
    private const val PREFS_NAME = "bharatchat_identity"
    private const val KEY_SECRET = "secret_key_hex"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun loadOrCreate(context: Context): Identity {
        val p = prefs(context)
        val existing = p.getString(KEY_SECRET, null)
        val secretKey = if (existing != null) {
            existing.hexToBytes()
        } else {
            val sk = Secp256k1Signer.randomSecretKey()
            p.edit().putString(KEY_SECRET, sk.toHex()).apply()
            sk
        }
        // Compute public key and ensure it is compressed
        val rawPubkey = Secp256k1Signer.pubkeyCreate(secretKey)
        val compressedPubkey = Secp256k1Signer.normalizePublicKey(rawPubkey)
        val pubkeyHex = compressedPubkey.toHex()
        return Identity(secretKey, pubkeyHex)
    }

    fun pairingCode(identity: Identity): String =
        "bharatchat://${identity.compressedPublicKeyHex}"

    fun pubkeyFromPairingCode(code: String): String {
        val trimmed = code.trim()
        require(trimmed.startsWith("bharatchat://")) {
            "Not a BharatChat pairing code (must start with 'bharatchat://')"
        }
        val hex = trimmed.removePrefix("bharatchat://").trim()
        require(hex.length == 66) {
            "Public key must be 66 hex characters, got ${hex.length}"
        }
        require(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid hex characters in public key"
        }
        return Secp256k1Signer.normalizePublicKeyHex(hex)
    }
}