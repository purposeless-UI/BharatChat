package com.droid

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE contactPubkey = :pubkey ORDER BY timestamp ASC")
    fun getMessagesForContact(pubkey: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE messages SET status = :status WHERE messageId = :packetId AND fromMe = 1")
    suspend fun updateStatus(packetId: String, status: Int)

    @Query("DELETE FROM messages WHERE contactPubkey = :pubkey")
    suspend fun deleteAllForContact(pubkey: String)

    // ✅ New method for deleting a single message by its unique messageId
    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)
}