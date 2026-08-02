package com.droid

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.hexToBytes
import com.droid.crypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Contact(
    val pubkeyHex: String,
    val name: String,
    val addedAt: Long
) {
    val xOnlyPubkeyHex: String = if (pubkeyHex.length == 66) pubkeyHex.drop(2) else pubkeyHex
}

@Suppress(
    "unused",
    "UnusedParameter",
    "KotlinExtension",
    "SpellCheckingInspection"
)
object ContactsStore {
    private const val PREFS_NAME = "bharatchat_contacts"
    private const val KEY_CONTACTS = "contacts_json"
    private const val TAG = "ContactsStore"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun xOnly(pubkeyHex: String): String =
        if (pubkeyHex.length == 66) pubkeyHex.drop(2) else pubkeyHex

    private fun ensureCompressed(pubkeyHex: String): String {
        val clean = pubkeyHex.trim().lowercase()
        val isHex = clean.all { it.isDigit() || it in 'a'..'f' }
        return when {
            // Handle full uncompressed 130‑hex (65 bytes) – scan returns this
            clean.length == 130 && clean.startsWith("04") && isHex -> {
                Secp256k1Signer.normalizePublicKeyHex(clean)
            }
            // Accept 66 hex starting with 02, 03, or 04 – convert to compressed
            clean.length == 66 && (clean.startsWith("02") || clean.startsWith("03") || clean.startsWith("04")) && isHex -> {
                Secp256k1Signer.normalizePublicKeyHex(clean)
            }
            clean.length == 64 && isHex ->
                throw IllegalArgumentException("X‑only key provided (64 hex). Please provide the full compressed key (66 hex) from the QR.")
            else -> throw IllegalArgumentException("Invalid public key format")
        }
    }

    suspend fun list(context: Context): List<Contact> = withContext(Dispatchers.IO) {
        val raw = prefs(context).getString(KEY_CONTACTS, null) ?: return@withContext emptyList()
        try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().map { key ->
                val entry = obj.getJSONObject(key)
                Contact(key, entry.getString("name"), entry.getLong("addedAt"))
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun isContact(context: Context, pubkeyHex: String): Boolean = withContext(Dispatchers.IO) {
        val raw = prefs(context).getString(KEY_CONTACTS, null) ?: return@withContext false
        try {
            val obj = JSONObject(raw)
            val xOnlyTarget = xOnly(pubkeyHex)
            obj.keys().asSequence().any { key -> key == pubkeyHex || xOnly(key) == xOnlyTarget }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun add(context: Context, pubkeyHex: String, name: String) = withContext(Dispatchers.IO) {
        val fullKey = try {
            ensureCompressed(pubkeyHex)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid key: ${e.message}")
            throw e
        }

        val p = prefs(context)
        val raw = p.getString(KEY_CONTACTS, null)
        val obj = try {
            if (raw != null) JSONObject(raw) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val xOnlyTarget = xOnly(fullKey)
        val keysToRemove = obj.keys().asSequence().filter { key ->
            xOnly(key) == xOnlyTarget
        }.toList()
        keysToRemove.forEach { obj.remove(it) }

        val entry = JSONObject().apply {
            put("name", name.ifBlank { fullKey.take(8) })
            put("addedAt", System.currentTimeMillis())
        }
        obj.put(fullKey, entry)
        p.edit().putString(KEY_CONTACTS, obj.toString()).apply()
    }

    suspend fun remove(context: Context, pubkeyHex: String) = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val raw = p.getString(KEY_CONTACTS, null) ?: return@withContext
        try {
            val obj = JSONObject(raw)
            val xOnlyTarget = xOnly(pubkeyHex)
            val keysToRemove = obj.keys().asSequence().filter { key ->
                xOnly(key) == xOnlyTarget
            }.toList()
            keysToRemove.forEach { obj.remove(it) }
            p.edit().putString(KEY_CONTACTS, obj.toString()).apply()
        } catch (_: Exception) {
            // ignore
        }
    }

    suspend fun rename(context: Context, pubkeyHex: String, newName: String) = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val raw = p.getString(KEY_CONTACTS, null) ?: return@withContext
        try {
            val obj = JSONObject(raw)
            val xOnlyTarget = xOnly(pubkeyHex)
            val matchingKey = obj.keys().asSequence().firstOrNull { key ->
                xOnly(key) == xOnlyTarget
            }
            if (matchingKey != null) {
                val entry = obj.getJSONObject(matchingKey)
                entry.put("name", newName.ifBlank { matchingKey.take(8) })
                p.edit().putString(KEY_CONTACTS, obj.toString()).apply()
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}