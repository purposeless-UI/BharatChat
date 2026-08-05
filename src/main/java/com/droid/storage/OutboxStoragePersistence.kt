package com.droid.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Persistent queue of unsent messages.
 */
data class OutboxEntry(
    val messageId: String,
    val recipientId: String,
    val plainText: String,
    var retryCount: Int = 0
) : Serializable

class OutboxStoragePersistence(private val context: Context) {
    companion object {
        private const val TAG = "OutboxStorage"
        private const val MAX_RETRIES = 8
        private const val MAX_ENTRIES = 100
        private const val FILE_NAME = "bharatchat_outbox.dat"
    }

    private val storageFile = File(context.filesDir, FILE_NAME)
    private val queue = mutableListOf<OutboxEntry>()

    init {
        loadFromDisk()
    }

    /**
     * Enqueues a new message.
     * @return true if added, false if the queue is full and we couldn't drop the oldest.
     */
    fun enqueue(messageId: String, recipientId: String, plainText: String): Boolean {
        if (queue.size >= MAX_ENTRIES) {
            Log.w(TAG, "Outbox full, dropping oldest entry")
            queue.removeAt(0)
        }
        val entry = OutboxEntry(messageId, recipientId, plainText)
        queue.add(entry)
        saveToDisk()
        Log.d(TAG, "Enqueued message $messageId for $recipientId")
        return true
    }

    /**
     * Returns a copy of all pending messages.
     */
    fun getAll(): List<OutboxEntry> = queue.toList()

    /**
     * Removes a specific message from the queue.
     */
    fun remove(messageId: String) {
        val removed = queue.removeAll { it.messageId == messageId }
        if (removed) {
            saveToDisk()
            Log.d(TAG, "Removed message $messageId")
        }
    }

    /**
     * Increments the retry counter; drops the message if it exceeds MAX_RETRIES.
     */
    fun incrementRetryOrDrop(messageId: String) {
        val entry = queue.find { it.messageId == messageId } ?: return
        entry.retryCount += 1
        if (entry.retryCount >= MAX_RETRIES) {
            Log.w(TAG, "Message $messageId dropped after $MAX_RETRIES retries")
            queue.remove(entry)
        }
        saveToDisk()
    }

    private fun saveToDisk() {
        try {
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(queue.toList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save outbox", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        try {
            context.openFileInput(FILE_NAME).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    val loaded = ois.readObject() as? List<OutboxEntry>
                    if (loaded != null) {
                        queue.clear()
                        queue.addAll(loaded)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load outbox", e)
        }
    }
}