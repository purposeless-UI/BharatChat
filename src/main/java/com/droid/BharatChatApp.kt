package com.droid

import android.app.Application
import android.util.Log

class BharatChatApp : Application() {
    companion object {
        private const val TAG = "BharatChatApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BharatChat application initialized successfully.")
    }
}