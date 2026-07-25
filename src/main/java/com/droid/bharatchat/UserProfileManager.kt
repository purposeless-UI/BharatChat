package com.droid.bharatchat

import android.content.Context
import android.util.Base64
import com.droid.crypto.MeshCryptoEngine
import java.util.UUID

object UserProfileManager {
    private const val PREFS_NAME = "bharatchat_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_IDENTITY_SECRET = "identity_secret_b64"

    fun getMyUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var username = prefs.getString(KEY_USERNAME, null)
        if (username == null) {
            username = "User_" + UUID.randomUUID().toString().substring(0, 5)
            prefs.edit().putString(KEY_USERNAME, username).apply()
        }
        return username
    }

    /**
     * Updates and saves a custom user handle/username in local preferences.
     */
    fun setMyUsername(context: Context, newUsername: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USERNAME, newUsername.trim()).apply()
    }

    /**
     * Returns this device's persistent presence-tag secret, generating and saving one
     * the first time it's needed. Previously PairingActivity called
     * MeshCryptoEngine.generateSessionKey() fresh every time it opened, so a peer who
     * scanned your QR code earlier would be matching against a key that no longer
     * existed on your device. This makes the identity stable across app restarts.
     */
    fun getMyIdentitySecret(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_IDENTITY_SECRET, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val newSecret = MeshCryptoEngine.generateSessionKey().encoded
        prefs.edit().putString(KEY_IDENTITY_SECRET, Base64.encodeToString(newSecret, Base64.NO_WRAP)).apply()
        return newSecret
    }

    fun getMyUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var userId = prefs.getString(KEY_USER_ID, null)
        if (userId == null) {
            userId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, userId).apply()
        }
        return userId
    }
}