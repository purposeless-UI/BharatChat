package com.droid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droid.ble.BleMeshManager
import com.droid.crypto.Secp256k1Signer
import com.droid.crypto.hexToBytes
import com.droid.crypto.toHex
import com.droid.storage.OutboxRetryScheduler
import com.droid.net.NostrMeshManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("StaticFieldLeak", "SpellCheckingInspection")
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"

    private var manager: BleMeshManager? = null
    private val messageListeners = ConcurrentHashMap<String, (String, String) -> Unit>()
    private val ackListeners = ConcurrentHashMap<String, (String, Int) -> Unit>()
    private val isStarting = AtomicBoolean(false)

    private var persistentMessageListener: ((String, String, String) -> Unit)? = null

    private var retryScheduler: OutboxRetryScheduler? = null
    private val peerIdToPubkey = ConcurrentHashMap<String, String>()
    private var nostrManager: NostrMeshManager? = null
    private var isNostrOnlyMode = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Handler for delayed outbox retry after network reconnection
    private val mainHandler = Handler(Looper.getMainLooper())

    // Store application context to recreate the identity when needed
    private var appContext: Context? = null

    // ✅ Periodic health check: restart if no relay connected for 30s
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (nostrManager?.isAnyRelayConnected() != true) {
                Log.w(TAG, "⚠️ No relay connected – forcing reconnect")
                onNetworkAvailable()
            }
            mainHandler.postDelayed(this, 30000)
        }
    }

    // =============== PUBLIC METHODS ===============

    fun getConnectedPeerCount(): Int = manager?.connectedPeerCount() ?: 0

    fun isDirectlyConnectedToPeer(peerId: String): Boolean =
        manager?.isDirectlyConnectedToPeer(peerId) ?: false

    @Synchronized
    @Suppress("unused")
    fun start(context: Context, mySecretKey: ByteArray, myPeerIdHex: String) {
        if (manager != null) {
            Log.d(TAG, "start: mesh already running")
            return
        }
        if (isStarting.getAndSet(true)) {
            Log.d(TAG, "start: already starting")
            return
        }

        try {
            appContext = context.applicationContext

            manager = BleMeshManager(
                context = context.applicationContext,
                mySecretKey = mySecretKey,
                myPeerIdHex = myPeerIdHex,
                onMessage = { from, packetId, text ->
                    persistentMessageListener?.invoke(from, packetId, text)
                    messageListeners.values.forEach { it(from, text) }
                },
                onAck = { packetId, ackType ->
                    ackListeners.values.forEach { it(packetId, ackType) }
                }
            )
            manager?.start()
            Log.d(TAG, "✅ Bluetooth Mesh started")

            retryScheduler = OutboxRetryScheduler(
                context = context.applicationContext,
                pubkeyLookup = { peerId ->
                    peerIdToPubkey[peerId]?.hexToBytes()
                }
            )
            retryScheduler?.start()
            Log.d(TAG, "✅ OutboxRetryScheduler started")

            val myXOnlyPubkeyHex = Secp256k1Signer.xOnlyPubkey(mySecretKey).toHex()
            startNostrRelay(mySecretKey, myXOnlyPubkeyHex)
            isNostrOnlyMode = false

            startNetworkListener(context)

            // ✅ Start periodic health check
            mainHandler.post(healthCheckRunnable)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh", e)
            manager = null
        } finally {
            isStarting.set(false)
        }
    }

    @Synchronized
    @Suppress("unused")
    fun startNostrOnly(context: Context, mySecretKey: ByteArray, myPeerIdHex: String) {
        if (nostrManager != null) {
            Log.d(TAG, "startNostrOnly: Nostr already running")
            return
        }
        if (isStarting.getAndSet(true)) {
            Log.d(TAG, "startNostrOnly: already starting")
            return
        }

        try {
            appContext = context.applicationContext

            val myXOnlyPubkeyHex = Secp256k1Signer.xOnlyPubkey(mySecretKey).toHex()
            startNostrRelay(mySecretKey, myXOnlyPubkeyHex)
            isNostrOnlyMode = true
            Log.d(TAG, "✅ Nostr-only mode started successfully")
            startNetworkListener(context)

            // ✅ Start periodic health check
            mainHandler.post(healthCheckRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start Nostr-only mode", e)
            nostrManager = null
        } finally {
            isStarting.set(false)
        }
    }

    private fun startNostrRelay(mySecretKey: ByteArray, myXOnlyPubkeyHex: String) {
        try {
            nostrManager = NostrMeshManager(
                myPeerIdHex = myXOnlyPubkeyHex,
                mySecretKey = mySecretKey,
                onMessage = { from, packetId, text ->
                    persistentMessageListener?.invoke(from, packetId, text)
                    messageListeners.values.forEach { it(from, text) }
                },
                onAck = { packetId, ackType ->
                    ackListeners.values.forEach { it(packetId, ackType) }
                }
            )
            nostrManager?.start()
            Log.d(TAG, "✅ Nostr relay manager started - messages can be sent even with Bluetooth OFF")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nostr start failed – Bluetooth will still work", e)
        }
    }

    @Synchronized
    fun stopBluetoothOnly() {
        try {
            manager?.stop()
        } catch (_: Exception) {}
        manager = null
        Log.d(TAG, "Bluetooth stopped (Nostr still running)")
    }

    @Synchronized
    fun stop() {
        stopNetworkListener()
        mainHandler.removeCallbacksAndMessages(null)

        // ✅ Stop the health check
        mainHandler.removeCallbacks(healthCheckRunnable)

        nostrManager?.stop()
        nostrManager = null

        try {
            manager?.stop()
        } catch (_: Exception) {}
        manager = null

        messageListeners.clear()
        ackListeners.clear()
        persistentMessageListener = null

        peerIdToPubkey.clear()
        retryScheduler?.stop()
        retryScheduler = null

        isNostrOnlyMode = false
        Log.d(TAG, "MeshServiceHolder stopped (Bluetooth + Nostr)")
    }

    // =============== NETWORK RECOVERY (RESTART NOSTR MANAGER) ===============

    @Synchronized
    fun onNetworkAvailable() {
        Log.d(TAG, "🌐 Internet restored – forcing full Nostr restart")

        mainHandler.removeCallbacksAndMessages(null)

        val ctx = appContext ?: run {
            Log.e(TAG, "App context missing – cannot restart")
            return
        }

        nostrManager?.stop()
        nostrManager = null

        mainHandler.postDelayed({
            try {
                val identity = IdentityStore.loadOrCreate(ctx)
                val myXOnlyPubkeyHex = Secp256k1Signer.xOnlyPubkey(identity.secretKey).toHex()
                startNostrRelay(identity.secretKey, myXOnlyPubkeyHex)
                Log.d(TAG, "✅ Nostr manager restarted")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restart Nostr manager", e)
                return@postDelayed
            }

            mainHandler.postDelayed(object : Runnable {
                private var attempts = 0
                override fun run() {
                    attempts++
                    if (nostrManager?.isAnyRelayConnected() == true) {
                        Log.d(TAG, "✅ Relay connected – triggering outbox retry")
                        retryScheduler?.triggerRetry()
                    } else if (attempts < 30) {
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        Log.w(TAG, "⏳ No relay after 30s – outbox will retry on next schedule")
                    }
                }
            }, 1000)
        }, 500)
    }

    // =============== Network listener ===============

    private fun startNetworkListener(context: Context) {
        if (connectivityManager != null) {
            Log.d(TAG, "Network listener already started")
            return
        }

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // ✅ Enhanced callback: also react to validated internet
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "📶 Internet available – triggering reconnect")
                onNetworkAvailable()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.d(TAG, "✅ Internet validated – triggering reconnect")
                    onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "📶 Internet lost")
            }
        }

        networkCallback = callback

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
            Log.d(TAG, "✅ Network listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun stopNetworkListener() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        connectivityManager = null
        networkCallback = null
        Log.d(TAG, "Network listener stopped")
    }

    // =============== Legacy listeners ===============

    @Suppress("unused")
    fun addMessageListener(key: String, listener: (String, String) -> Unit) {
        messageListeners[key] = listener
    }

    @Suppress("unused")
    fun removeMessageListener(key: String) {
        messageListeners.remove(key)
    }

    @Suppress("unused")
    fun addAckListener(key: String, listener: (String, Int) -> Unit) {
        ackListeners[key] = listener
    }

    @Suppress("unused")
    fun removeAckListener(key: String) {
        ackListeners.remove(key)
    }

    @Suppress("unused")
    fun setPersistentMessageListener(listener: (String, String, String) -> Unit) {
        persistentMessageListener = listener
    }

    fun getRetryScheduler(): OutboxRetryScheduler? = retryScheduler

    fun registerPeer(peerId: String, compressedPubkeyHex: String) {
        peerIdToPubkey[peerId] = compressedPubkeyHex
    }

    fun sendMessage(
        recipientPeerId: String,
        recipientPubkey: ByteArray,
        plaintext: String
    ): String? {
        Log.d(TAG, "📤 sendMessage called for peer: $recipientPeerId")
        Log.d(TAG, "   Bluetooth manager available? ${manager != null}")
        Log.d(TAG, "   Nostr manager available? ${nostrManager != null}")
        Log.d(TAG, "   Nostr-only mode? $isNostrOnlyMode")

        val bleResult = manager?.sendMessage(recipientPeerId, recipientPubkey, plaintext)
        if (bleResult != null) {
            Log.d(TAG, "✅ Message sent via Bluetooth, packetId: $bleResult")
            return bleResult
        }

        Log.d(TAG, "🔄 Bluetooth failed or unavailable, trying Nostr fallback...")
        if (nostrManager == null) {
            Log.e(TAG, "❌ Nostr manager is null - cannot send via internet")
            return null
        }

        val recipientXOnlyHex = recipientPubkey.copyOfRange(1, 33).toHex()
        Log.d(TAG, "   Recipient x‑only pubkey: $recipientXOnlyHex")

        val nostrResult = nostrManager?.sendMessage(recipientXOnlyHex, recipientPubkey, plaintext)
        if (nostrResult != null) {
            val mode = if (isNostrOnlyMode) " (Nostr-only mode)" else ""
            Log.d(TAG, "✅ Message sent via Nostr (internet relay)$mode, packetId: $nostrResult")
            return nostrResult
        }

        Log.e(TAG, "❌ Both Bluetooth and Nostr failed to send message")
        return null
    }

    fun isNostrAvailable(): Boolean = nostrManager != null

    @Suppress("unused")
    fun isBluetoothAvailable(): Boolean = manager != null

    @Suppress("unused")
    fun isNostrOnlyMode(): Boolean = isNostrOnlyMode
}