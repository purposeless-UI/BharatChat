package com.droid.storage

import android.content.Context
import android.util.Log
import com.droid.crypto.MeshCryptoEngine
import javax.crypto.SecretKey
import java.io.File

data class QueuedMessage(
    val messageId: String,
    val recipientId: String,
    val encryptedPayload: String,
    var retryCount: Int = 0
)

class MeshOutboxManager(private val context: Context) {
    companion object {
        private const val TAG = "MeshOutboxManager"
        private const val MAX_RETRIES = 8
        private const val OUTBOX_FILE = "bharatchat_outbox.dat"
    }

    private val outboxQueue = mutableListOf<QueuedMessage>()

    /**
     * Enqueues an un-deliverable message into the secure outbox.
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
        outboxQueue.removeAll { it.messageId == messageId }
        Log.d(TAG, "Message $messageId successfully cleared from outbox.")
    }

    /**
     * Increments retry counter and drops messages that exceed the max retry cap.
     */
    fun incrementRetryOrDrop(messageId: String) {
        val msg = outboxQueue.find { it.messageId == messageId } ?: return
        msg.retryCount += 1
        if (msg.retryCount >= MAX_RETRIES) {
            Log.w(TAG, "Message $messageId dropped after exceeding max retry cap ($MAX_RETRIES).")
            outboxQueue.remove(msg)
        }
    }
}