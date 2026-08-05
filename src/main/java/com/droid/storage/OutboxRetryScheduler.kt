package com.droid.storage

import android.content.Context
import android.util.Log
import com.droid.ble.BleMeshManager
import kotlinx.coroutines.*

/**
 * Periodically retries sending pending messages from the outbox.
 */
class OutboxRetryScheduler(
    private val context: Context,
    private val meshProvider: () -> BleMeshManager?,
    private val pubkeyLookup: (recipientPeerId: String) -> ByteArray? // compressed public key bytes
) {
    companion object {
        private const val TAG = "OutboxRetryScheduler"
        private const val RETRY_INTERVAL_MS = 5000L
    }

    private val outbox = OutboxStoragePersistence(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var started = false

    /**
     * Starts the background retry loop.
     */
    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            while (isActive) {
                try {
                    processPendingMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing outbox", e)
                }
                delay(RETRY_INTERVAL_MS)
            }
        }
        Log.d(TAG, "OutboxRetryScheduler started")
    }

    /**
     * Stops the retry loop and clears resources.
     */
    fun stop() {
        started = false
        job?.cancel()
        job = null
        scope.cancel()
        Log.d(TAG, "OutboxRetryScheduler stopped")
    }

    /**
     * Enqueues a new message to be retried later.
     */
    fun enqueueMessage(recipientPeerId: String, plainText: String) {
        val messageId = java.util.UUID.randomUUID().toString()
        outbox.enqueue(messageId, recipientPeerId, plainText)
    }

    private suspend fun processPendingMessages() {
        val mesh = meshProvider()
        if (mesh == null || mesh.connectedPeerCount() == 0) {
            return // no connection, skip this cycle
        }

        val pending = outbox.getAll()
        if (pending.isEmpty()) return

        Log.d(TAG, "Processing ${pending.size} pending outbox messages")

        for (entry in pending) {
            val pubkey = pubkeyLookup(entry.recipientId)
            if (pubkey == null) {
                Log.w(TAG, "Recipient ${entry.recipientId} not found, dropping")
                outbox.remove(entry.messageId)
                continue
            }

            val packetId = mesh.sendMessage(entry.recipientId, pubkey, entry.plainText)
            if (packetId != null) {
                Log.d(TAG, "Outbox message ${entry.messageId} sent (packetId=$packetId)")
                outbox.remove(entry.messageId)
                // Optionally: notify UI that the message was sent
            } else {
                outbox.incrementRetryOrDrop(entry.messageId)
                Log.w(TAG, "Outbox message ${entry.messageId} failed, retry ${entry.retryCount+1}")
            }
        }
    }
}