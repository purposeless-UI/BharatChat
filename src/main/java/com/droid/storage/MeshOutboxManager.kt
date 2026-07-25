package com.droid.storage

import android.content.Context
import android.util.Log
import com.droid.crypto.MeshCryptoEngine
import javax.crypto.SecretKey
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

data class QueuedMessage(
    val messageId: String,
    val recipientId: String,
    val encryptedPayload: String,
    var retryCount: Int = 0
) : Serializable

class MeshOutboxManager(private val context: Context) {
    companion object {
        private const val TAG = "MeshOutboxManager"
        private const val MAX_RETRIES = 8
        private const val OUTBOX_FILENAME = "bharatchat_outbox.dat"
    }

    private val outboxFile: File = File(context.filesDir, OUTBOX_FILENAME)
    private val outboxQueue = mutableListOf<QueuedMessage>()

    init {
        loadQueueFromFile()
    }

    /**
     * Enqueues an un-deliverable message into the secure outbox and persists it.
     */
    fun enqueueMessage(messageId: String, recipientId: String, plainText: String, contactKey: SecretKey): Boolean {
        if (outboxQueue.size >= 100) {
            Log.w(TAG, "Outbox limit reached (100 messages). Dropping oldest entry.")
            outboxQueue.removeAt(0)
        }

        // Seal payload using AES-GCM (acts as our local storage encryption and offline seal)
        val encryptedPayload = MeshCryptoEngine.encrypt(plainText, contactKey)
        
        val queued = QueuedMessage(
            messageId = messageId,
            recipientId = recipientId,
            encryptedPayload = encryptedPayload
        )
        
        outboxQueue.add(queued)
        saveQueueToFile()
        Log.d(TAG, "Message $messageId queued in offline outbox for recipient: $recipientId")
        return true
    }

    /**
     * Returns all pending messages ready for delivery retry.
     */
    fun getPendingMessages(): List<QueuedMessage> {
        return outboxQueue.toList()
    }

    /**
     * Removes a message from the queue once a delivery acknowledgement or read receipt clears it.
     */
    fun removeMessage(messageId: String) {
        val removed = outboxQueue.removeAll { it.messageId == messageId }
        if (removed) {
            saveQueueToFile()
            Log.d(TAG, "Message $messageId successfully cleared from outbox.")
        }
    }

    /**
     * Increments retry counter, drops messages that exceed the max retry cap, and persists the state.
     */
    fun incrementRetryOrDrop(messageId: String) {
        val msg = outboxQueue.find { it.messageId == messageId } ?: return
        msg.retryCount += 1
        if (msg.retryCount >= MAX_RETRIES) {
            Log.w(TAG, "Message $messageId dropped after exceeding max retry cap ($MAX_RETRIES).")
            outboxQueue.remove(msg)
        }
        saveQueueToFile()
    }

    /**
     * Persists the current outbox queue to internal file storage securely.
     */
    private fun saveQueueToFile() {
        try {
            context.openFileOutput(OUTBOX_FILENAME, Context.MODE_PRIVATE).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(outboxQueue.toList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save outbox queue to file: ${e.message}", e)
        }
    }

    /**
     * Loads the saved outbox queue from internal file storage upon initialization.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadQueueFromFile() {
        if (!outboxFile.exists()) return
        try {
            context.openFileInput(OUTBOX_FILENAME).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    val savedList = ois.readObject() as? List<QueuedMessage>
                    if (savedList != null) {
                        outboxQueue.clear()
                        outboxQueue.addAll(savedList)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load outbox queue from file: ${e.message}", e)
        }
    }
}