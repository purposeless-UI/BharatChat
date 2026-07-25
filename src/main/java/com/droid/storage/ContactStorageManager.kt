package com.droid.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

class ContactStorageManager(context: Context) {
    companion object {
        private const val PREF_NAME = "bharatchat_secure_contacts"
        private const val KEY_CONTACT_LIST = "trusted_contact_secrets"
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
}