package com.droid.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droid.crypto.SealedBox
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

@Suppress("SpellCheckingInspection")
class BleMeshManager(
    private val context: Context,
    private val mySecretKey: ByteArray,
    private val myPeerIdHex: String,
    // ✅ Updated: onMessage now receives packetId as well
    onMessage: (fromPeerIdHex: String, packetId: String, plaintext: String) -> Unit,
    private val onAck: (originalPacketId: String, ackType: Int) -> Unit
) {
    companion object {
        private const val TAG = "BleMeshManager"
        private const val HELLO_INTERVAL_MS = 5000L
        private const val ROUTE_QUALITY_DECAY_FACTOR = 0.9f
        private const val ROUTE_EXPIRY_MS = 60000L
        private const val ROUTE_MIN_QUALITY = 5
        private const val MAX_FRAGMENT_MEMORY_BYTES = 1024 * 1024 // 1 MB
    }

    data class RoutingEntry(
        var nextHopAddress: String,
        var quality: Int = 10,
        var lastSeen: Long = System.currentTimeMillis()
    )

    private val routingTable = ConcurrentHashMap<String, RoutingEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onMessageCallback = onMessage
    private val seenPackets = SeenPacketCache()
    // Maps device Bluetooth address -> peer ID of the directly connected node
    private val addressToPeerId = ConcurrentHashMap<String, String>()
    private val isRunning = AtomicBoolean(false)
    private val fragmentCache = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val fragmentTimestamps = ConcurrentHashMap<String, Long>()
    private val relayReversePath = ConcurrentHashMap<String, String>()
    private val fragmentAddresses = ConcurrentHashMap<String, String>()
    private val fragmentTotalBytes = AtomicLong(0) // global cap

    private val server = BleGattServerManager(
        context = context,
        myPeerIdHex = myPeerIdHex,
        onPacketReceived = { packet, address -> handleIncomingPacket(packet, address) }
    )

    private val client = BleGattClientManager(
        context = context,
        onPeerConnected = { peerId, address, rssi ->
            // This is the ONLY place we set addressToPeerId
            addressToPeerId[address] = peerId
            // Use RSSI to boost initial quality (range -100..0, add up to 10)
            val rssiBoost = if (rssi in -100..0) (rssi + 100) / 10 else 0
            routingTable[peerId] = RoutingEntry(address, quality = 20 + rssiBoost)
            Log.d(TAG, "Client connected to $peerId at $address (RSSI=$rssi, quality=${20 + rssiBoost})")
        },
        onPeerDisconnected = { address ->
            addressToPeerId.remove(address)
            routingTable.entries.removeAll { it.value.nextHopAddress == address }
            Log.d(TAG, "Client disconnected from $address")
        },
        onPacketReceived = { packet, address -> handleIncomingPacket(packet, address) }
    )

    fun start() {
        if (isRunning.getAndSet(true)) return
        server.start()
        client.startScanning()
        mainHandler.post(cleanupRunnable)
        mainHandler.post(helloRunnable)
        Log.d(TAG, "Mesh started, peerId=${myPeerIdHex.take(12)}...")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        server.stop()
        client.stopScanning()
        addressToPeerId.clear()
        seenPackets.clean()
        fragmentCache.clear()
        fragmentTimestamps.clear()
        fragmentAddresses.clear()
        fragmentTotalBytes.set(0)
        routingTable.clear()
        mainHandler.removeCallbacks(cleanupRunnable)
        mainHandler.removeCallbacks(helloRunnable)
        Log.d(TAG, "Mesh stopped")
    }

    fun isDirectlyConnectedToPeer(peerIdHex: String): Boolean =
        addressToPeerId.values.contains(peerIdHex)

    fun isRunning(): Boolean = isRunning.get()
    fun connectedPeerCount(): Int = server.connectedAddresses().size + client.connectedAddresses().size

    /**
     * Sends a message to a peer. Returns the packet ID (for ACK tracking) on success, null on failure.
     */
    fun sendMessage(recipientPeerIdHex: String, recipientCompressedPubkey: ByteArray, plaintext: String): String? {
        try {
            val sealed = SealedBox.seal(recipientCompressedPubkey, plaintext.toByteArray(Charsets.UTF_8))
            val payloadJson = JSONObject()
                .put("ephemeralPubkey", sealed.ephemeralPubkeyHex)
                .put("iv", sealed.ivHex)
                .put("ciphertext", sealed.ciphertextHex)
            val payloadBytes = payloadJson.toString().toByteArray(Charsets.UTF_8)

            val packetId = BlePacket.newPacketId()

            if (payloadBytes.size <= BleConstants.MAX_FRAGMENT_PAYLOAD_SIZE) {
                val packet = BlePacket(
                    type = BleConstants.TYPE_DATA,
                    ttl = BleConstants.TTL_INITIAL,
                    timestamp = System.currentTimeMillis(),
                    senderPeerIdHex = myPeerIdHex,
                    recipientPeerIdHex = recipientPeerIdHex,
                    packetIdHex = packetId,
                    payload = payloadBytes
                )
                seenPackets.markSeen(packet.packetIdHex)
                return if (sendPacketSmart(packet)) packetId else null
            } else {
                val maxSize = BleConstants.MAX_FRAGMENT_PAYLOAD_SIZE
                val totalFragments = (payloadBytes.size + maxSize - 1) / maxSize
                for (i in 0 until totalFragments) {
                    val start = i * maxSize
                    val end = minOf(start + maxSize, payloadBytes.size)
                    val chunk = payloadBytes.copyOfRange(start, end)
                    val fragPacket = BlePacket.createFragmentPacket(
                        originalPacketId = packetId,
                        fragmentIndex = i,
                        totalFragments = totalFragments,
                        fragmentData = chunk,
                        senderPeerIdHex = myPeerIdHex,
                        recipientPeerIdHex = recipientPeerIdHex
                    )
                    if (!sendPacketSmart(fragPacket)) {
                        Log.e(TAG, "Failed to send fragment $i of $totalFragments")
                        return null
                    }
                }
                return packetId
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            return null
        }
    }

    private fun sendPacketSmart(packet: BlePacket): Boolean {
        val recipient = packet.recipientPeerIdHex
        if (recipient.isEmpty()) {
            return floodSend(packet)
        }

        val route = routingTable[recipient]
        if (route != null && route.quality >= ROUTE_MIN_QUALITY) {
            val address = route.nextHopAddress
            val ok = client.writeTo(address, packet) || server.notify(address, packet)
            if (ok) {
                Log.d(TAG, "✅ Routed to $recipient via $address (quality=${route.quality})")
                route.quality = (route.quality + 1).coerceAtMost(100)
                route.lastSeen = System.currentTimeMillis()
                return true
            } else {
                Log.w(TAG, "Route to $recipient via $address failed, removing")
                routingTable.remove(recipient)
            }
        }

        Log.d(TAG, "No valid route to $recipient, flooding")
        return floodSend(packet)
    }

    private fun floodSend(packet: BlePacket): Boolean {
        val allAddresses = server.connectedAddresses() + client.connectedAddresses()
        if (allAddresses.isEmpty()) {
            Log.w(TAG, "No connected peers to send to")
            return false
        }
        var sentToAnyone = false
        for (address in allAddresses) {
            val ok = client.writeTo(address, packet) || server.notify(address, packet)
            if (ok) sentToAnyone = true
        }
        return sentToAnyone
    }

    private fun handleIncomingPacket(packet: BlePacket, fromAddress: String) {
        try {
            if (seenPackets.hasSeen(packet.packetIdHex)) return
            seenPackets.markSeen(packet.packetIdHex)

            // Do NOT overwrite addressToPeerId here – it is set only on direct connection.
            // Learn multi-hop route: if the sender is not the immediate neighbor, store a route via fromAddress.
            val immediatePeer = addressToPeerId[fromAddress]
            if (immediatePeer != null && immediatePeer != packet.senderPeerIdHex) {
                // This packet came from a different peer via this address (relayed)
                val senderId = packet.senderPeerIdHex
                val existing = routingTable[senderId]
                if (existing == null) {
                    routingTable[senderId] = RoutingEntry(fromAddress, quality = 5) // lower initial quality
                    Log.d(TAG, "Learned multi-hop route to $senderId via $fromAddress")
                } else {
                    // Update existing route if this path is better or fresher
                    existing.quality = max(existing.quality, 5)
                    existing.nextHopAddress = fromAddress
                    existing.lastSeen = System.currentTimeMillis()
                }
            }

            when (packet.type) {
                BleConstants.TYPE_DATA -> {
                    if (packet.recipientPeerIdHex == myPeerIdHex) {
                        deliverToApp(packet, fromAddress)
                        return
                    }
                    relayOnward(packet, excludingAddress = fromAddress)
                }
                BleConstants.TYPE_DATA_FRAGMENT -> {
                    handleFragment(packet, fromAddress)
                }
                BleConstants.TYPE_DELIVERY_ACK, BleConstants.TYPE_READ_ACK -> {
                    val originalPacketId = String(packet.payload, Charsets.UTF_8)
                    Log.d(TAG, "Received ACK type ${packet.type} for packet $originalPacketId")
                    mainHandler.post { onAck(originalPacketId, packet.type) }
                    if (packet.recipientPeerIdHex != myPeerIdHex) {
                        relayOnward(packet, excludingAddress = fromAddress)
                    }
                }
                BleConstants.TYPE_HELLO -> {
                    val senderPeerId = packet.senderPeerIdHex
                    if (senderPeerId == myPeerIdHex) return
                    val entry = routingTable[senderPeerId]
                    if (entry == null) {
                        routingTable[senderPeerId] = RoutingEntry(fromAddress, quality = 10)
                        Log.d(TAG, "New peer discovered: $senderPeerId via $fromAddress")
                    } else {
                        entry.quality = (entry.quality + 1).coerceAtMost(100)
                        entry.lastSeen = System.currentTimeMillis()
                        entry.nextHopAddress = fromAddress
                        Log.v(TAG, "Updated route to $senderPeerId quality=${entry.quality}")
                    }
                }
                else -> Log.d(TAG, "Unknown packet type: ${packet.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIncomingPacket error", e)
        }
    }

    private val helloRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            val helloPayload = myPeerIdHex.toByteArray(Charsets.UTF_8)
            val packet = BlePacket(
                type = BleConstants.TYPE_HELLO,
                ttl = 1,
                timestamp = System.currentTimeMillis(),
                senderPeerIdHex = myPeerIdHex,
                recipientPeerIdHex = "",
                packetIdHex = BlePacket.newPacketId(),
                payload = helloPayload
            )
            val allAddresses = server.connectedAddresses() + client.connectedAddresses()
            for (address in allAddresses) {
                client.writeTo(address, packet) || server.notify(address, packet)
            }
            val now = System.currentTimeMillis()
            for ((peerId, entry) in routingTable) {
                entry.quality = (entry.quality * ROUTE_QUALITY_DECAY_FACTOR).toInt()
                if (now - entry.lastSeen > ROUTE_EXPIRY_MS || entry.quality < 1) {
                    routingTable.remove(peerId)
                    Log.d(TAG, "Route to $peerId expired (quality=${entry.quality})")
                }
            }
            mainHandler.postDelayed(this, HELLO_INTERVAL_MS)
        }
    }

    private fun handleFragment(packet: BlePacket, fromAddress: String) {
        val fragmentData = BlePacket.extractFragmentData(packet)
        if (fragmentData == null) {
            Log.w(TAG, "Failed to parse fragment")
            return
        }
        val originalId = fragmentData.originalPacketId
        fragmentTimestamps[originalId] = System.currentTimeMillis()
        fragmentAddresses[originalId] = fromAddress

        val parts = fragmentCache.getOrPut(originalId) { ConcurrentHashMap() }

        // Check if we already have this fragment index
        if (parts.containsKey(fragmentData.index)) {
            Log.d(TAG, "Duplicate fragment ${fragmentData.index} for $originalId, ignoring")
            return
        }

        // Add fragment and update byte count
        val addedBytes = fragmentData.data.size
        parts[fragmentData.index] = fragmentData.data
        fragmentTotalBytes.addAndGet(addedBytes.toLong())

        // Global cap: if exceeded, evict oldest incomplete set
        if (fragmentTotalBytes.get() > MAX_FRAGMENT_MEMORY_BYTES) {
            val oldestId = fragmentTimestamps.keys.minByOrNull { fragmentTimestamps[it] ?: Long.MAX_VALUE }
            if (oldestId != null) {
                Log.w(TAG, "Fragment memory cap exceeded, evicting $oldestId")
                fragmentCache.remove(oldestId)?.values?.forEach { arr -> fragmentTotalBytes.addAndGet(-arr.size.toLong()) }
                fragmentTimestamps.remove(oldestId)
                fragmentAddresses.remove(oldestId)
            }
        }

        if (parts.size == fragmentData.total) {
            val sortedKeys = parts.keys.sorted()
            val fullPayload = sortedKeys.flatMap { parts[it]!!.toList() }.toByteArray()
            fragmentCache.remove(originalId)
            fragmentTimestamps.remove(originalId)
            // subtract bytes of this set
            parts.values.forEach { fragmentTotalBytes.addAndGet(-it.size.toLong()) }
            val storedAddress = fragmentAddresses.remove(originalId) ?: ""

            val dataPacket = BlePacket(
                type = BleConstants.TYPE_DATA,
                ttl = packet.ttl,
                timestamp = packet.timestamp,
                senderPeerIdHex = packet.senderPeerIdHex,
                recipientPeerIdHex = myPeerIdHex,
                packetIdHex = originalId,
                payload = fullPayload
            )
            seenPackets.markSeen(originalId)
            deliverToApp(dataPacket, storedAddress)
        } else {
            Log.d(TAG, "Received fragment ${fragmentData.index + 1}/${fragmentData.total} for $originalId")
        }
    }

    private fun deliverToApp(packet: BlePacket, fromAddress: String) {
        try {
            val payloadJson = JSONObject(String(packet.payload, Charsets.UTF_8))
            val sealed = SealedBox.Sealed(
                ephemeralPubkeyHex = payloadJson.getString("ephemeralPubkey"),
                ivHex = payloadJson.getString("iv"),
                ciphertextHex = payloadJson.getString("ciphertext")
            )
            val plaintext = String(SealedBox.unseal(mySecretKey, sealed), Charsets.UTF_8)

            sendAck(packet.senderPeerIdHex, packet.packetIdHex, BleConstants.TYPE_DELIVERY_ACK, immediateAddress = fromAddress)
            // ✅ Now pass packetId as the second argument
            onMessageCallback(packet.senderPeerIdHex, packet.packetIdHex, plaintext)

            mainHandler.postDelayed({
                sendAck(packet.senderPeerIdHex, packet.packetIdHex, BleConstants.TYPE_READ_ACK, immediateAddress = fromAddress)
            }, 2000)
        } catch (e: Exception) {
            Log.w(TAG, "deliverToApp failed", e)
        }
    }

    private fun relayOnward(packet: BlePacket, excludingAddress: String) {
        if (packet.ttl <= 0) return
        val allAddresses = (server.connectedAddresses() + client.connectedAddresses()) - excludingAddress
        if (allAddresses.isEmpty()) return

        // Adaptive relay probability based on peer count
        val peerCount = allAddresses.size
        val relayProbability = when {
            peerCount <= 3 -> 1.0f
            peerCount <= 6 -> 0.8f
            else -> 0.6f
        }

        if (Random.nextFloat() > relayProbability) {
            Log.d(TAG, "Relay suppressed by adaptive probability (peerCount=$peerCount)")
            return
        }

        relayReversePath[packet.packetIdHex] = excludingAddress
        val forwarded = packet.withDecrementedTtl()

        // Jittered delay per peer (10–50 ms) to avoid collisions
        for (address in allAddresses) {
            val delay = (10..50).random().toLong()
            mainHandler.postDelayed({
                if (isRunning.get()) {
                    client.writeTo(address, forwarded) || server.notify(address, forwarded)
                }
            }, delay)
        }
    }

    private fun sendAck(targetPeerId: String, originalPacketId: String, ackType: Int, immediateAddress: String? = null) {
        try {
            val ackPayload = originalPacketId.toByteArray(Charsets.UTF_8)
            val packet = BlePacket(
                type = ackType,
                ttl = BleConstants.TTL_ACK,
                timestamp = System.currentTimeMillis(),
                senderPeerIdHex = myPeerIdHex,
                recipientPeerIdHex = targetPeerId,
                packetIdHex = BlePacket.newPacketId(),
                payload = ackPayload
            )

            // 1. Find address
            var address = immediateAddress
            if (address == null) {
                address = addressToPeerId.entries.firstOrNull { it.value == targetPeerId }?.key
            }
            if (address == null) {
                val route = routingTable[targetPeerId]
                if (route != null && route.quality >= ROUTE_MIN_QUALITY) {
                    address = route.nextHopAddress
                }
            }
            if (address == null) {
                address = relayReversePath.remove(originalPacketId)
                if (address != null) {
                    Log.d(TAG, "Routing ACK for $originalPacketId back via relay node $address")
                }
            }

            // 2. Try direct send
            var sent = false
            if (address != null) {
                sent = client.writeTo(address, packet) || server.notify(address, packet)
                if (sent) {
                    Log.d(TAG, "✅ ACK sent directly to $address for $originalPacketId")
                } else {
                    Log.w(TAG, "Direct ACK to $address failed")
                }
            }

            // 3. If direct send failed (or no address), flood to all connected peers
            if (!sent) {
                Log.d(TAG, "⚠️ Flooding ACK for $originalPacketId to all peers")
                val allAddresses = server.connectedAddresses() + client.connectedAddresses()
                if (allAddresses.isNotEmpty()) {
                    for (addr in allAddresses) {
                        client.writeTo(addr, packet) || server.notify(addr, packet)
                    }
                } else {
                    Log.w(TAG, "No connected peers to flood ACK")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAck failed", e)
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            val now = System.currentTimeMillis()
            fragmentTimestamps.entries.removeAll { (id, time) ->
                if (now - time > 60000) {
                    fragmentCache.remove(id)?.values?.forEach { fragmentTotalBytes.addAndGet(-it.size.toLong()) }
                    true
                } else false
            }
            mainHandler.postDelayed(this, 30000)
        }
    }
}