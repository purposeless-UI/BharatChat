package com.droid.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.droid.AppDatabase
import com.droid.Contact
import com.droid.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object MessageHandler {
    private const val TAG = "MessageHandler"
    private const val STATUS_DELIVERED = 1

    @Suppress("UNUSED_PARAMETER") // fromPeerId is kept for API compatibility
    suspend fun processIncomingMessage(
        context: Context,
        fromPeerId: String,
        packetId: String,
        plaintext: String,
        contact: Contact,
        onMessageInserted: (MessageEntity) -> Unit = {}
    ) {
        val db = AppDatabase.getInstance(context)

        // Check if this message already exists (deduplication)
        val existing = withContext(Dispatchers.IO) {
            db.messageDao().getMessageByPacketIdAndContact(packetId, contact.pubkey)
        }
        if (existing != null) {
            Log.d(TAG, "Duplicate message $packetId ignored")
            return
        }

        val entity = withContext(Dispatchers.IO) {
            val trimmed = plaintext.trim()
            var voiceFilePath = ""
            var voiceDuration = 0L
            var isVoice = false
            var displayText = trimmed

            try {
                val json = JSONObject(trimmed)
                val type = json.optString("type", "text")
                if (type == "voice") {
                    isVoice = true
                    val audioBase64 = json.getString("audio")
                    voiceDuration = json.optInt("duration", 0).toLong()
                    val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
                    val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.3gp")
                    file.outputStream().use { it.write(audioBytes) }
                    voiceFilePath = file.absolutePath
                    displayText = "[Voice Message]"
                    Log.d(TAG, "Voice file saved for ${contact.name} at $voiceFilePath, size=${audioBytes.size}")
                }
            } catch (_: Exception) {
                // Not JSON – keep as text
            }

            val entity = MessageEntity(
                contactPubkey = contact.pubkey,
                text = displayText,
                fromMe = false,
                timestamp = System.currentTimeMillis(),
                status = STATUS_DELIVERED,
                messageId = packetId,
                type = if (isVoice) 1 else 0,
                voiceDuration = voiceDuration,
                voiceFilePath = voiceFilePath
            )
            db.messageDao().insert(entity)
            Log.d(TAG, "Persistent message saved for ${contact.name} (type=${if (isVoice) "voice" else "text"})")
            entity
        }

        onMessageInserted(entity)
    }
}