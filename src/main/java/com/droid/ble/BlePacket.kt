package com.droid.ble

import com.droid.crypto.hexToBytes
import com.droid.crypto.toHex
import java.nio.ByteBuffer

/**
 * Compact binary packet.
 * type 0x02 = sealed message (chat content)
 * type 0x03 = delivery acknowledgment
 * type 0x04 = read acknowledgment
 * type 0x05 = data fragment (for large messages)
 */
@Suppress("SpellCheckingInspection")
data class BlePacket(
    val version: Int = 1,
    val type: Int,
    val ttl: Int,
    val timestamp: Long,
    val senderPeerIdHex: String,
    val recipientPeerIdHex: String,
    val packetIdHex: String,
    val payload: ByteArray
) {
    fun toBinary(): ByteArray {
        val senderBytes = senderPeerIdHex.hexToBytes()
        val recipientBytes = recipientPeerIdHex.hexToBytes()
        val packetIdBytes = packetIdHex.hexToBytes()
        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 8 + 8 + 8 + 8 + 2 + payload.size)
        buffer.put(version.toByte())
        buffer.put(type.toByte())
        buffer.put(ttl.toByte())
        buffer.putLong(timestamp)
        buffer.put(senderBytes)
        buffer.put(recipientBytes)
        buffer.put(packetIdBytes)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    fun withDecrementedTtl(): BlePacket = copy(ttl = ttl - 1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlePacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (ttl != other.ttl) return false
        if (timestamp != other.timestamp) return false
        if (senderPeerIdHex != other.senderPeerIdHex) return false
        if (recipientPeerIdHex != other.recipientPeerIdHex) return false
        if (packetIdHex != other.packetIdHex) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderPeerIdHex.hashCode()
        result = 31 * result + recipientPeerIdHex.hashCode()
        result = 31 * result + packetIdHex.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun fromBinary(data: ByteArray): BlePacket? {
            return try {
                val buffer = ByteBuffer.wrap(data)
                val version = buffer.get().toInt()
                val type = buffer.get().toInt()
                val ttl = buffer.get().toInt()
                val timestamp = buffer.long
                val senderBytes = ByteArray(8).also { buffer.get(it) }
                val recipientBytes = ByteArray(8).also { buffer.get(it) }
                val packetIdBytes = ByteArray(8).also { buffer.get(it) }
                val payloadLen = buffer.short.toInt()
                val payload = ByteArray(payloadLen).also { buffer.get(it) }
                BlePacket(version, type, ttl, timestamp,
                    senderBytes.toHex(), recipientBytes.toHex(), packetIdBytes.toHex(), payload)
            } catch (_: Exception) { null }
        }

        fun newPacketId(): String {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.toHex()
        }

        // ============================================================
        //  Fragment support
        // ============================================================

        /**
         * Creates a fragment packet.
         * @param originalPacketId The unique ID of the original (full) message.
         * @param fragmentIndex 0‑based index of this fragment.
         * @param totalFragments Total number of fragments.
         * @param fragmentData The chunk of the original payload.
         * @param senderPeerIdHex Sender peer ID.
         * @param recipientPeerIdHex Recipient peer ID.
         * @return A BlePacket of type TYPE_DATA_FRAGMENT.
         */
        fun createFragmentPacket(
            originalPacketId: String,
            fragmentIndex: Int,
            totalFragments: Int,
            fragmentData: ByteArray,
            senderPeerIdHex: String,
            recipientPeerIdHex: String
        ): BlePacket {
            val idBytes = originalPacketId.hexToBytes()
            val payload = ByteBuffer.allocate(8 + 2 + 2 + fragmentData.size)
                .put(idBytes)
                .putShort(fragmentIndex.toShort())
                .putShort(totalFragments.toShort())
                .put(fragmentData)
                .array()
            return BlePacket(
                type = BleConstants.TYPE_DATA_FRAGMENT,
                ttl = BleConstants.TTL_INITIAL,
                timestamp = System.currentTimeMillis(),
                senderPeerIdHex = senderPeerIdHex,
                recipientPeerIdHex = recipientPeerIdHex,
                packetIdHex = BlePacket.newPacketId(),
                payload = payload
            )
        }

        /**
         * Extracts fragment metadata and data from a fragment packet.
         * @param packet A BlePacket of type TYPE_DATA_FRAGMENT.
         * @return FragmentData if successful, null otherwise.
         */
        fun extractFragmentData(packet: BlePacket): FragmentData? {
            if (packet.type != BleConstants.TYPE_DATA_FRAGMENT) return null
            val buffer = ByteBuffer.wrap(packet.payload)
            return try {
                val idBytes = ByteArray(8).also { buffer.get(it) }
                val originalPacketId = idBytes.toHex()
                val fragmentIndex = buffer.short.toInt()
                val totalFragments = buffer.short.toInt()
                val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                FragmentData(originalPacketId, fragmentIndex, totalFragments, data)
            } catch (_: Exception) { null }
        }
    }
}

/**
 * Data extracted from a fragment packet.
 */
data class FragmentData(
    val originalPacketId: String,
    val index: Int,
    val total: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FragmentData

        if (originalPacketId != other.originalPacketId) return false
        if (index != other.index) return false
        if (total != other.total) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = originalPacketId.hashCode()
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** First 8 bytes of the x‑only public key (16 hex chars) – stable BLE peer ID. */
fun peerIdFromPubkey(pubkeyHex: String): String =
    (if (pubkeyHex.length == 66) pubkeyHex.drop(2) else pubkeyHex).take(16)