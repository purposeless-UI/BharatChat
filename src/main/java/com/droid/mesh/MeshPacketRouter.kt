package com.droid.mesh

import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap

data class MeshPacket(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    var ttl: Int,
    val encryptedPayload: String
) {
    /**
     * Serializes the packet into a byte array for BLE transmission.
     */
    fun serialize(): ByteArray {
        val raw = "ID:$messageId|FROM:$senderId|TO:$recipientId|TTL:$ttl|MSG:$encryptedPayload"
        return raw.toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Deserializes raw incoming BLE bytes back into a MeshPacket.
         */
        fun deserialize(bytes: ByteArray): MeshPacket? {
            try {
                val raw = String(bytes, Charsets.UTF_8)
                val parts = raw.split("|")
                if (parts.size < 5) return null

                val messageId = parts[0].removePrefix("ID:")
                val senderId = parts[1].removePrefix("FROM:")
                val recipientId = parts[2].removePrefix("TO:")
                val ttl = parts[3].removePrefix("TTL:").toIntOrNull() ?: 7
                val encryptedPayload = parts[4].removePrefix("MSG:")

                return MeshPacket(messageId, senderId, recipientId, ttl, encryptedPayload)
            } catch (e: Exception) {
                Log.e("MeshPacket", "Failed to deserialize packet bytes", e)
                return null
            }
        }
    }
}

class MeshPacketRouter(
    private val currentUserId: String,
    private val onMessageReadyToDeliver: (MeshPacket) -> Unit,
    private val onPacketRelay: (MeshPacket) -> Unit
) {
    companion object {
        private const val TAG = "MeshPacketRouter"
        private const val MAX_SEEN_CACHE_SIZE = 1000
        private const val INITIAL_TTL = 7
    }

    // LRU Cache for duplicate suppression
    private val seenCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(MAX_SEEN_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
                return size > MAX_SEEN_CACHE_SIZE
            }
        }
    )

    /**
     * Ingress point for any incoming packet received from a BLE advertisement or connection.
     */
    fun handleIncomingPacket(packet: MeshPacket) {
        // 1. Check duplicate cache
        if (seenCache.containsKey(packet.messageId)) {
            Log.d(TAG, "Duplicate packet dropped: ${packet.messageId}")
            return
        }

        // Mark as seen with current timestamp
        seenCache[packet.messageId] = System.currentTimeMillis()

        // 2. Check TTL expiration
        if (packet.ttl <= 0) {
            Log.w(TAG, "Packet expired (TTL reached 0): ${packet.messageId}")
            return
        }

        // 3. Deliver if intended for us or if it is a broadcast/target match
        if (packet.recipientId == currentUserId || packet.recipientId == "BROADCAST" || packet.recipientId == "Unknown_Peer") {
            onMessageReadyToDeliver(packet)
        }

        // 4. Multi-hop Relay decrementing TTL (relay for others if TTL allows)
        if (packet.ttl > 1) {
            packet.ttl -= 1
            Log.d(TAG, "Relaying packet ${packet.messageId} with new TTL: ${packet.ttl}")
            onPacketRelay(packet)
        }
    }

    /**
     * Creates a fresh outgoing packet ready for multi-hop mesh broadcast.
     */
    fun createOutgoingPacket(messageId: String, senderId: String, recipientId: String, encryptedPayload: String): MeshPacket {
        val packet = MeshPacket(
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            ttl = INITIAL_TTL,
            encryptedPayload = encryptedPayload
        )
        // Mark our own sent packet as seen so we don't echo it back
        seenCache[messageId] = System.currentTimeMillis()
        return packet
    }
}