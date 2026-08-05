package com.droid.storage

import android.content.Context
import android.util.Log
import com.droid.AppDatabase
import com.droid.MeshServiceHolder
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodically retries sending pending messages from the outbox.
 */
class OutboxRetryScheduler(
    private val context: Context,
    private val pubkeyLookup: (recipientPeerId: String) -> ByteArray? // compressed public key bytes
) {
    companion object {
        private const val TAG = "OutboxRetryScheduler"
        private val RETRY_INTERVAL = 5.seconds
        // ✅ Global sequence counter (atomic, thread‑safe)
        private val sequenceCounter = AtomicLong(0)
    }

    private val outbox = OutboxStoragePersistence(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var started = false

    // Database reference for updating packetId
    private val db: AppDatabase by lazy { AppDatabase.getInstance(context) }

    // ✅ Lock to prevent concurrent processing
    private val isProcessing = AtomicBoolean(false)

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
                delay(RETRY_INTERVAL)
            }
        }
        Log.d(TAG, "OutboxRetryScheduler started")
    }

    fun stop() {
        started = false
        job?.cancel()
        job = null
        scope.cancel()
        Log.d(TAG, "OutboxRetryScheduler stopped")
    }

    /**
     * Returns the next sequence number to assign to a message.
     * Must be called on the main thread (or any thread, as it's atomic).
     */
    fun getNextSequence(): Long = sequenceCounter.incrementAndGet()

    /**
     * Enqueues a message for later delivery.
     * @param recipientPeerId The peer ID of the recipient.
     * @param plainText The message text.
     * @param dbMessageId The database ID of the corresponding MessageEntity.
     * @param sequenceNumber The sequence number (obtained from getNextSequence()).
     */
    fun enqueueMessage(recipientPeerId: String, plainText: String, dbMessageId: Long, sequenceNumber: Long) {
        val messageId = java.util.UUID.randomUUID().toString()
        outbox.enqueue(messageId, recipientPeerId, plainText, dbMessageId, sequenceNumber)
        Log.d(TAG, "📝 Message $messageId queued for $recipientPeerId (seq=$sequenceNumber, db=$dbMessageId)")
    }

    /**
     * Triggers an immediate retry of all pending outbox messages.
     * This is meant to be called when internet connectivity is restored,
     * so messages don't wait for the next timer tick.
     */
    fun triggerRetry() {
        if (!started) {
            Log.w(TAG, "triggerRetry called but scheduler is not started")
            return
        }
        scope.launch {
            Log.d(TAG, "🔁 Triggering immediate outbox retry")
            processPendingMessages()
        }
    }

    /**
     * Processes all pending outbox messages. This method is called periodically by the internal timer
     * and can also be triggered externally via [triggerRetry].
     */
    private suspend fun processPendingMessages() {
        // ✅ Prevent concurrent execution – if already running, skip this round
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "processPendingMessages already running, skipping")
            return
        }

        try {
            // ✅ Sort by dbMessageId (guaranteed FIFO), then timestamp, then sequenceNumber
            val pending = outbox.getAll().sortedWith(
                compareBy<OutboxEntry> { it.dbMessageId }
                    .thenBy { it.timestamp }
                    .thenBy { it.sequenceNumber }
            )
            if (pending.isEmpty()) return

            Log.d(TAG, "📤 Processing ${pending.size} pending outbox messages")

            for (entry in pending) {
                val pubkey = pubkeyLookup(entry.recipientId)
                if (pubkey == null) {
                    Log.w(TAG, "Recipient ${entry.recipientId} not found, dropping")
                    outbox.remove(entry.messageId)
                    continue
                }

                val packetId = MeshServiceHolder.sendMessage(entry.recipientId, pubkey, entry.plainText)

                if (packetId != null) {
                    Log.d(TAG, "✅ Outbox message ${entry.messageId} sent via Bluetooth or ,Nostr, packetId=$packetId")
                    // Update the database with the packetId so ACKs can be processed
                    try {
                        db.messageDao().updatePacketId(entry.dbMessageId, packetId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update packetId for dbMessageId=${entry.dbMessageId}", e)
                    }
                    outbox.remove(entry.messageId)
                    // ✅ Success: continue to next message
                } else {
                    // ❌ Failure: increment retry and STOP processing further messages
                    outbox.incrementRetryOrDrop(entry.messageId)
                    Log.w(TAG, "❌ Outbox message ${entry.messageId} failed, retry ${entry.retryCount + 1}")
                    break // Stop processing to preserve FIFO order
                }
            }
        } finally {
            // Release the lock so the next run can proceed
            isProcessing.set(false)
        }
    }
}