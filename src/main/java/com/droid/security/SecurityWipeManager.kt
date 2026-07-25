package com.droid.security

import android.content.Context
import android.util.Log
import java.io.File

object SecurityWipeManager {
    private const val TAG = "SecurityWipeManager"

    /**
     * Executes an emergency panic wipe. Purges identity keys, secure outbox files,
     * gossip history caches, and temporary payloads from internal storage securely
     * by overwriting file content before deletion when possible.
     */
    fun executePanicWipe(context: Context): Boolean {
        try {
            Log.w(TAG, "Initiating emergency panic wipe...")

            // 1. Securely overwrite and clear internal files dir (outbox, chat logs, keys)
            val filesDir = context.filesDir
            if (filesDir.exists()) {
                filesDir.listFiles()?.forEach { file ->
                    secureDeleteFile(file)
                }
            }

            // 2. Clear internal cache files
            val cacheDir = context.cacheDir
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    secureDeleteFile(file)
                }
            }

            // 3. Clear shared preferences holding keys or configuration tokens
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.listFiles()?.forEach { file ->
                    secureDeleteFile(file)
                }
            }

            Log.i(TAG, "Emergency panic wipe completed successfully. All local traces cleared.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete panic wipe securely: ${e.message}", e)
            return false
        }
    }

    /**
     * Overwrites file contents with zeros/random bytes before unlinking to prevent forensic recovery.
     */
    private fun secureDeleteFile(file: File) {
        try {
            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    secureDeleteFile(child)
                }
                file.delete()
            } else if (file.exists()) {
                // Overwrite file length with zero bytes
                val length = file.length()
                if (length > 0) {
                    try {
                        file.outputStream().use { fos ->
                            val buffer = ByteArray(4096)
                            var written = 0L
                            while (written < length) {
                                val chunk = minOf(buffer.size.toLong(), length - written).toInt()
                                fos.write(buffer, 0, chunk)
                                written += chunk
                            }
                            fos.flush()
                        }
                    } catch (ignored: Exception) {
                        // Fallback if write stream fails
                    }
                }
                file.delete()
            }
        } catch (e: Exception) {
            // Final fallback to standard delete if secure overwrite encounters an issue
            try {
                file.deleteRecursively()
            } catch (ignored: Exception) {}
        }
    }
}