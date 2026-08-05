package com.droid.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.droid.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // ✅ Use IGNORE to silently skip duplicate messageId inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    // ✅ NEW: Insert and return the generated row ID
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAndGetId(message: MessageEntity): Long

    // ✅ NEW: Update the packet ID for a message identified by its database ID
    @Query("UPDATE messages SET messageId = :packetId WHERE id = :dbMessageId")
    suspend fun updatePacketId(dbMessageId: Long, packetId: String)

    @Query("SELECT * FROM messages WHERE contactPubkey = :pubkey ORDER BY timestamp ASC")
    fun getMessagesForContact(pubkey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :packetId AND contactPubkey = :pubkey LIMIT 1")
    suspend fun getMessageByPacketIdAndContact(packetId: String, pubkey: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE messages SET status = :status WHERE messageId = :packetId AND fromMe = 1")
    suspend fun updateStatus(packetId: String, status: Int)

    @Query("DELETE FROM messages WHERE contactPubkey = :pubkey")
    suspend fun deleteAllForContact(pubkey: String)

    @Suppress("unused")
    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)
}