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
    val dbMessageId: Long,
    val timestamp: Long,                 // time of enqueue (millis)
    val sequenceNumber: Long,           // ✅ unique, monotonic order assigned at send time
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
    private val lock = Any()

    init {
        loadFromDisk()
        migrateOldEntries()
    }

    /**
     * Migration for old entries that don't have a sequenceNumber field.
     * Uses timestamp as fallback order.
     */
    private fun migrateOldEntries() {
        synchronized(lock) {
            var changed = false
            for (i in queue.indices) {
                val entry = queue[i]
                // If sequenceNumber is 0 (default for old entries), set it to timestamp
                if (entry.sequenceNumber == 0L) {
                    queue[i] = entry.copy(sequenceNumber = entry.timestamp)
                    changed = true
                }
            }
            if (changed) {
                saveToDisk()
                Log.d(TAG, "Migrated old outbox entries with sequenceNumber = timestamp")
            }
        }
    }

    /**
     * Enqueues a new message.
     * @param messageId Unique identifier for this outbox entry.
     * @param recipientId Peer ID of the recipient.
     * @param plainText The message text.
     * @param dbMessageId The Room database ID of the corresponding MessageEntity.
     * @param sequenceNumber The sequence number (from OutboxRetryScheduler.getNextSequence()).
     * @return true if added; false if the queue is full, and we couldn't drop the oldest.
     */
    fun enqueue(
        messageId: String,
        recipientId: String,
        plainText: String,
        dbMessageId: Long,
        sequenceNumber: Long
    ): Boolean {
        synchronized(lock) {
            if (queue.size >= MAX_ENTRIES) {
                Log.w(TAG, "Outbox full, dropping oldest entry")
                queue.removeAt(0)
            }
            val entry = OutboxEntry(
                messageId = messageId,
                recipientId = recipientId,
                plainText = plainText,
                dbMessageId = dbMessageId,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = sequenceNumber
            )
            queue.add(entry)
            saveToDisk()
            Log.d(TAG, "Enqueued message $messageId for $recipientId (seq=$sequenceNumber, db=$dbMessageId)")
            return true
        }
    }

    fun getAll(): List<OutboxEntry> = synchronized(lock) { queue.toList() }

    fun remove(messageId: String) {
        synchronized(lock) {
            val removed = queue.removeAll { it.messageId == messageId }
            if (removed) {
                saveToDisk()
                Log.d(TAG, "Removed message $messageId")
            }
        }
    }

    fun incrementRetryOrDrop(messageId: String) {
        synchronized(lock) {
            val entry = queue.find { it.messageId == messageId } ?: return
            entry.retryCount += 1
            if (entry.retryCount >= MAX_RETRIES) {
                Log.w(TAG, "Message $messageId dropped after $MAX_RETRIES retries")
                queue.remove(entry)
            }
            saveToDisk()
        }
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