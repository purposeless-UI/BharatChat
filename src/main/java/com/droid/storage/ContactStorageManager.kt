package com.droid.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class ContactStorageManager(context: Context) {
    companion object {
        private const val PREF_NAME = "bharatchat_secure_contacts"
        private const val KEY_CONTACT_LIST = "trusted_contact_secrets"
        private const val CHAT_PREFIX = "chat_history_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Saves a newly paired contact's secret token into local secure storage.
     */
    fun saveContactSecret(secretString: String) {
        val currentSet = prefs.getStringSet(KEY_CONTACT_LIST, mutableSetOf()) ?: mutableSetOf()
        val updatedSet = currentSet.toMutableSet()
        updatedSet.add(secretString)
        prefs.edit().putStringSet(KEY_CONTACT_LIST, updatedSet).apply()
    }

    /**
     * Retrieves all trusted contact secrets as raw byte arrays for the BLE presence scanner.
     */
    fun getTrustedContactSecrets(): List<ByteArray> {
        val currentSet = prefs.getStringSet(KEY_CONTACT_LIST, emptySet()) ?: emptySet()
        return currentSet.map { base64Token ->
            try {
                Base64.decode(base64Token, Base64.NO_WRAP)
            } catch (e: Exception) {
                base64Token.toByteArray(Charsets.UTF_8)
            }
        }
    }

    /**
     * Saves a chat message to a specific peer's dedicated history log.
     */
    fun saveMessageToPeerHistory(peerName: String, messageEntry: String) {
        val key = "$CHAT_PREFIX$peerName"
        val currentHistory = prefs.getStringSet(key, mutableSetOf()) ?: mutableSetOf()
        val updatedHistory = currentHistory.toMutableSet()
        updatedHistory.add(messageEntry)
        prefs.edit().putStringSet(key, updatedHistory).apply()
    }

    /**
     * Retrieves all chat messages for a specific peer's chat panel.
     */
    fun getPeerChatHistory(peerName: String): List<String> {
        val key = "$CHAT_PREFIX$peerName"
        val historySet = prefs.getStringSet(key, emptySet()) ?: emptySet()
        // Return sorted or maintained order if stored with timestamps
        return historySet.toList()
    }

    /**
     * Clears or deletes all chat history for a specific peer panel on demand.
     */
    fun clearPeerChatHistory(peerName: String) {
        val key = "$CHAT_PREFIX$peerName"
        prefs.edit().remove(key).apply()
    }
}