package com.droid.bharatchat
import com.droid.bharatchat.UserProfileManager

import android.content.Context
import java.util.UUID

object UserProfileManager {
    private const val PREFS_NAME = "bharatchat_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_USER_ID = "user_id"

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