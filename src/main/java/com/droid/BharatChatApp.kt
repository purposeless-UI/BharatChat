package com.droid

import android.app.Application
import android.util.Log

class BharatChatApp : Application() {

    companion object {
        private const val TAG = "BharatChatApp"
        lateinit var appContext: Application
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        Log.d(TAG, "Application started")

        try {
            IdentityStore.loadOrCreate(this)
            Log.d(TAG, "Identity loaded/created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load identity", e)
        }
    }
}