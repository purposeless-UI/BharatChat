package com.droid.voice

import android.util.Base64
import android.util.Log
import com.droid.MeshServiceHolder
import com.droid.storage.OutboxRetryScheduler
import org.json.JSONObject
import java.io.File

class VoiceMessageSender(
    private val retryScheduler: OutboxRetryScheduler?
) {
    private val TAG = "VoiceMessageSender"

    /**
     * Sends a voice message.
     * @param file The audio file.
     * @param duration Duration in seconds.
     * @param recipientPeerId Peer ID of the recipient.
     * @param recipientPubkey Compressed public key of the recipient.
     * @param dbMessageId Database ID of the corresponding MessageEntity (required).
     * @param sequenceNumber The global send order number (required for FIFO).
     * @return The packet ID (String?) if sent immediately, or null if queued.
     */
    fun sendVoiceMessage(
        file: File,
        duration: Long,
        recipientPeerId: String,
        recipientPubkey: ByteArray,
        dbMessageId: Long,
        sequenceNumber: Long   // ✅ new parameter
    ): String? {
        try {
            val audioBytes = file.readBytes()
            val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val payloadJson = JSONObject().apply {
                put("type", "voice")
                put("audio", audioBase64)
                put("duration", duration)
            }
            val payload = payloadJson.toString()

            val packetId = MeshServiceHolder.sendMessage(recipientPeerId, recipientPubkey, payload)

            if (packetId != null) {
                Log.d(TAG, "✅ Voice message sent via Bluetooth or Nostr, packetId=$packetId")
                return packetId
            } else {
                // Both failed – queue with the DB ID and sequence number
                retryScheduler?.enqueueMessage(recipientPeerId, payload, dbMessageId, sequenceNumber)
                Log.d(TAG, "⏳ Voice message queued for later (db=$dbMessageId, seq=$sequenceNumber)")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice message", e)
            return null
        }
    }
}