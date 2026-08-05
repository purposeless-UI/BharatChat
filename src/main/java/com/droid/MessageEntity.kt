package com.droid

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["messageId"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactPubkey: String,
    val text: String,
    val fromMe: Boolean,
    val timestamp: Long,
    val status: Int, // 0=SENT, 1=DELIVERED, 2=READ
    val messageId: String? = null, // packet ID for ACK tracking – nullable for old rows
    val type: Int = 0,          // 0=text, 1=voice
    val voiceDuration: Long = 0, // duration in seconds
    val voiceFilePath: String = "" // local file path for received voice messages
)