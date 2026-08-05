package com.droid.net

import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.*
import kotlinx.serialization.json.*
import com.droid.crypto.SealedBox
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.toHex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

@Suppress("SpellCheckingInspection")
class NostrMeshManager(
    private val myPeerIdHex: String,
    private val mySecretKey: ByteArray,
    private val relayUrls: List<String> = listOf(
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://offchain.pub",
        "wss://relay.nostr.info",
        "wss://nostr.bitcoiner.social",
        "wss://relay.nostr.bg",
        "wss://relay.nostr.pet"
    ),
    private val onMessage: (fromPeerId: String, packetId: String, plaintext: String) -> Unit,
    private val onAck: (packetId: String, ackType: Int) -> Unit
) {
    private val myXOnlyPubkeyHex: String = Secp256k1Signer.xOnlyPubkey(mySecretKey).toHex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    private val workingRelays = ConcurrentHashMap<String, Boolean>()
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val receivedEvents = mutableSetOf<String>()
    private val connectionAttempts = ConcurrentHashMap<String, Int>()

    private val maxFailures = 3
    private val baseRetryDelayMs = 2000L
    private val maxRetryDelayMs = 300000L // 5 minutes

    // ✅ Lock to prevent overlapping start/stop calls
    private val lock = Any()

    // ============================================================
    //  Lifecycle (synchronized)
    // ============================================================

    fun start() {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            Log.d("NostrMeshManager", "🚀 Starting with ${relayUrls.size} relays")
            for (relayUrl in relayUrls) connectToRelay(relayUrl)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
            mainHandler.removeCallbacksAndMessages(null)
            for ((_, ws) in webSockets) ws.close(1000, null)
            webSockets.clear()
            workingRelays.clear()
            failureCounts.clear()
            connectionAttempts.clear()
            receivedEvents.clear()
            Log.d("NostrMeshManager", "Stopped")
        }
    }

    // ============================================================
    //  Reconnect (not synchronized; only called from main thread)
    // ============================================================

    fun reconnectAll() {
        Log.d("NostrMeshManager", "🔄 Reconnecting all relays")
        for ((url, ws) in webSockets) {
            ws.close(1000, "Reconnecting")
        }
        webSockets.clear()
        workingRelays.clear()
        failureCounts.clear()
        connectionAttempts.clear()
        for (relayUrl in relayUrls) {
            connectToRelay(relayUrl)
        }
    }

    // ============================================================
    //  Private helpers (unchanged)
    // ============================================================

    private fun connectToRelay(relayUrl: String) {
        val attempt = connectionAttempts.getOrDefault(relayUrl, 0) + 1
        connectionAttempts[relayUrl] = attempt
        Log.d("NostrMeshManager", "🔗 [$attempt] Connecting to $relayUrl")
        try {
            val request = Request.Builder().url(relayUrl).addHeader("User-Agent", "BharatChat/1.0").build()
            val ws = client.newWebSocket(request, createListener(relayUrl))
            webSockets[relayUrl] = ws
        } catch (e: Exception) {
            Log.e("NostrMeshManager", "❌ Failed connect to $relayUrl", e)
            workingRelays[relayUrl] = false
            handleRelayFailure(relayUrl)
        }
    }

    private fun createListener(relayUrl: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                workingRelays[relayUrl] = true
                failureCounts[relayUrl] = 0
                connectionAttempts[relayUrl] = 0
                Log.d("NostrMeshManager", "✅ Connected to $relayUrl")
                val sub = """["REQ","sub1",{"kinds":[1,1000,1001],"#p":["$myXOnlyPubkeyHex"]}]"""
                webSocket.send(sub)
                Log.d("NostrMeshManager", "📡 Subscribed on $relayUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val preview = if (text.length > 200) text.take(200) + "..." else text
                    Log.d("NostrMeshManager", "📩 $relayUrl: $preview")
                    val event = Json.decodeFromString<JsonArray>(text)
                    if (event[0].jsonPrimitive.content == "EVENT") {
                        val eventData = event[2].jsonObject
                        val kind = eventData["kind"]?.jsonPrimitive?.int ?: 0

                        if (kind == 1000 || kind == 1001) {
                            val originalId = eventData["content"]?.jsonPrimitive?.content ?: return
                            onAck(originalId, kind)
                            return
                        }

                        if (kind != 1) return

                        val tags = eventData["tags"]?.jsonArray ?: return
                        val recipientPubkey = tags.firstOrNull {
                            it.jsonArray[0].jsonPrimitive.content == "p"
                        }?.jsonArray?.get(1)?.jsonPrimitive?.content ?: return
                        if (recipientPubkey != myXOnlyPubkeyHex) return

                        val senderPubkey = eventData["pubkey"]?.jsonPrimitive?.content ?: return
                        val senderPeerId = senderPubkey.take(16)

                        val content = eventData["content"]?.jsonPrimitive?.content ?: return
                        val id = eventData["id"]?.jsonPrimitive?.content ?: return
                        if (id in receivedEvents) return
                        receivedEvents.add(id)

                        val payloadJson = Json.decodeFromString<JsonObject>(content)
                        val sealed = SealedBox.Sealed(
                            ephemeralPubkeyHex = payloadJson["ephemeralPubkey"]?.jsonPrimitive?.content ?: "",
                            ivHex = payloadJson["iv"]?.jsonPrimitive?.content ?: "",
                            ciphertextHex = payloadJson["ciphertext"]?.jsonPrimitive?.content ?: ""
                        )
                        val plaintext = String(SealedBox.unseal(mySecretKey, sealed), Charsets.UTF_8)

                        onMessage(senderPeerId, id, plaintext)
                        // ✅ Pass the sender's pubkey so the ACK can be routed back
                        sendDeliveryAck(id, senderPubkey)
                    }
                } catch (_: Exception) { /* ignore */ }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("NostrMeshManager", "⚠️ Failure on $relayUrl", t)
                handleRelayFailure(relayUrl)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("NostrMeshManager", "🔌 Closed $relayUrl: $code $reason")
                handleRelayFailure(relayUrl)
            }
        }
    }

    private fun handleRelayFailure(relayUrl: String) {
        val count = failureCounts.getOrDefault(relayUrl, 0) + 1
        failureCounts[relayUrl] = count
        if (count >= maxFailures) {
            workingRelays[relayUrl] = false
            webSockets.remove(relayUrl)?.close(1000, "Too many failures")
            Log.d("NostrMeshManager", "⛔ Removed $relayUrl after $count failures")
            val delay = minOf(baseRetryDelayMs * (1L shl (count - maxFailures)), maxRetryDelayMs)
            mainHandler.postDelayed({
                if (isRunning) {
                    Log.d("NostrMeshManager", "🔄 Retrying $relayUrl after ${delay}ms")
                    connectToRelay(relayUrl)
                }
            }, delay)
        }
    }

    // ============================================================
    //  Send Message
    // ============================================================

    fun sendMessage(recipientPeerId: String, recipientPubkey: ByteArray, plaintext: String): String? {
        Log.d("NostrMeshManager", "📤 Sending to $recipientPeerId")

        try {
            val sealed = SealedBox.seal(recipientPubkey, plaintext.toByteArray(Charsets.UTF_8))
            val payload = JsonObject(mapOf(
                "ephemeralPubkey" to JsonPrimitive(sealed.ephemeralPubkeyHex),
                "iv" to JsonPrimitive(sealed.ivHex),
                "ciphertext" to JsonPrimitive(sealed.ciphertextHex)
            ))
            val content = payload.toString()
            val createdAt = System.currentTimeMillis() / 1000
            val kind = 1

            val recipientXOnly = recipientPubkey.copyOfRange(1, 33).toHex()
            val tags = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(recipientXOnly)))))

            val hashArray = JsonArray(listOf(
                JsonPrimitive(0),
                JsonPrimitive(myXOnlyPubkeyHex),
                JsonPrimitive(createdAt),
                JsonPrimitive(kind),
                tags,
                JsonPrimitive(content)
            ))
            val hashString = hashArray.toString()
            val hashBytes = hashString.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(hashBytes)
            val id = digest.toHex()

            val signature = Secp256k1Signer.signSchnorr(digest, mySecretKey)
            val sigHex = signature.toHex()

            val event = JsonObject(mapOf(
                "id" to JsonPrimitive(id),
                "pubkey" to JsonPrimitive(myXOnlyPubkeyHex),
                "created_at" to JsonPrimitive(createdAt),
                "kind" to JsonPrimitive(kind),
                "tags" to tags,
                "content" to JsonPrimitive(content),
                "sig" to JsonPrimitive(sigHex)
            ))

            val message = JsonArray(listOf(JsonPrimitive("EVENT"), event)).toString()
            Log.d("NostrMeshManager", "📦 Event $id created")

            var sent = false
            var sentCount = 0
            for ((url, ws) in webSockets) {
                if (workingRelays[url] == true) {
                    try {
                        ws.send(message)
                        sent = true
                        sentCount++
                    } catch (_: Exception) {
                        handleRelayFailure(url)
                    }
                }
            }
            Log.d("NostrMeshManager", "📊 Sent to $sentCount relays")
            return if (sent) id else null
        } catch (e: Exception) {
            Log.e("NostrMeshManager", "❌ Send failed", e)
            return null
        }
    }

    // ============================================================
    //  Delivery ACK – now includes "p" tag for routing
    // ============================================================

    private fun sendDeliveryAck(originalPacketId: String, senderPubkey: String) {
        try {
            val createdAt = System.currentTimeMillis() / 1000
            val kind = 1000
            // ✅ Add "p" tag with the original sender's pubkey
            val tags = JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(senderPubkey)))
            ))

            val hashArray = JsonArray(listOf(
                JsonPrimitive(0),
                JsonPrimitive(myXOnlyPubkeyHex),
                JsonPrimitive(createdAt),
                JsonPrimitive(kind),
                tags,
                JsonPrimitive(originalPacketId)
            ))
            val hashString = hashArray.toString()
            val hashBytes = hashString.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(hashBytes)
            val id = digest.toHex()

            val signature = Secp256k1Signer.signSchnorr(digest, mySecretKey)
            val sigHex = signature.toHex()

            val event = JsonObject(mapOf(
                "id" to JsonPrimitive(id),
                "pubkey" to JsonPrimitive(myXOnlyPubkeyHex),
                "created_at" to JsonPrimitive(createdAt),
                "kind" to JsonPrimitive(kind),
                "tags" to tags,
                "content" to JsonPrimitive(originalPacketId),
                "sig" to JsonPrimitive(sigHex)
            ))

            val message = JsonArray(listOf(JsonPrimitive("EVENT"), event)).toString()
            for ((url, ws) in webSockets) {
                if (workingRelays[url] == true) {
                    try { ws.send(message) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    // ============================================================
    //  Utility methods
    // ============================================================

    @Suppress("unused")
    fun getConnectedRelayCount(): Int = workingRelays.values.count { it }

    @Suppress("unused")
    fun getWorkingRelays(): List<String> = webSockets.keys.filter { workingRelays[it] == true }

    fun isAnyRelayConnected(): Boolean = workingRelays.values.any { it }
}