package com.droid.mesh

import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap

data class MeshPacket(
    val messageId: String,
    val senderId: String,
    var ttl: Int,
    val encryptedPayload: String
)

class MeshPacketRouter(
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

        // 2. Check if packet is intended for us or needs to be relayed
        if (packet.ttl <= 0) {
            Log.w(TAG, "Packet expired (TTL reached 0): ${packet.messageId}")
            return
        }

        // Trigger local delivery/decryption handler
        onMessageReadyToDeliver(packet)

        // 3. Multi-hop Relay decrementing TTL
        if (packet.ttl > 1) {
            packet.ttl -= 1
            Log.d(TAG, "Relaying packet ${packet.messageId} with new TTL: ${packet.ttl}")
            onPacketRelay(packet)
        }
    }

    /**
     * Creates a fresh outgoing packet ready for multi-hop mesh broadcast.
     */
    fun createOutgoingPacket(messageId: String, senderId: String, encryptedPayload: String): MeshPacket {
        val packet = MeshPacket(
            messageId = messageId,
            senderId = senderId,
            ttl = INITIAL_TTL,
            encryptedPayload = encryptedPayload
        )
        // Mark our own sent packet as seen so we don't echo it back
        seenCache[messageId] = System.currentTimeMillis()
        return packet
    }
}