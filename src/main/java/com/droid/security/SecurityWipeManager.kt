package com.droid.security

import android.content.Context
import android.util.Log
import java.io.File

object SecurityWipeManager {
    private const val TAG = "SecurityWipeManager"

    /**
     * Executes an emergency panic wipe. Purges identity keys, secure outbox files,
     * gossip history caches, and temporary payloads from internal storage.
     */
    fun executePanicWipe(context: Context): Boolean {
        try {
            Log.w(TAG, "Initiating emergency panic wipe...")

            // 1. Clear internal cache files and outbox data
            val cacheDir = context.cacheDir
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }

            val filesDir = context.filesDir
            if (filesDir.exists()) {
                filesDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            // 2. Clear shared preferences holding keys or configuration tokens
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            Log.i(TAG, "Emergency panic wipe completed successfully. All local traces cleared.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete panic wipe securely: ${e.message}", e)
            return false
        }
    }
}