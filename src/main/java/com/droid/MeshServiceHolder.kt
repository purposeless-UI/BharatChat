package com.droid

import android.content.Context
import android.util.Log
import com.droid.ble.BleMeshManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("StaticFieldLeak")
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"

    private var manager: BleMeshManager? = null
    private val messageListeners = ConcurrentHashMap<String, (String, String) -> Unit>()
    private val ackListeners = ConcurrentHashMap<String, (String, Int) -> Unit>()
    private val isStarting = AtomicBoolean(false)

    // ✅ New persistent listener – saves all incoming messages to DB
    private var persistentMessageListener: ((String, String) -> Unit)? = null

    @Synchronized
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
            manager = BleMeshManager(
                context = context.applicationContext,
                mySecretKey = mySecretKey,
                myPeerIdHex = myPeerIdHex,
                onMessage = { from, text ->
                    // 1. Persistent listener (saves to DB)
                    persistentMessageListener?.invoke(from, text)
                    // 2. Activity‑registered listeners (UI updates)
                    messageListeners.values.forEach { it(from, text) }
                },
                onAck = { packetId, ackType ->
                    ackListeners.values.forEach { it(packetId, ackType) }
                }
            )
            manager?.start()
            Log.d(TAG, "MeshServiceHolder started with ACK support")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh", e)
            manager = null
        } finally {
            isStarting.set(false)
        }
    }

    @Synchronized
    fun stop() {
        try {
            manager?.stop()
        } catch (_: Exception) {
        }
        manager = null
        messageListeners.clear()
        ackListeners.clear()
        persistentMessageListener = null   // ✅ clear persistent listener
        Log.d(TAG, "MeshServiceHolder stopped")
    }

    fun current(): BleMeshManager? = manager

    fun addMessageListener(key: String, listener: (String, String) -> Unit) {
        messageListeners[key] = listener
    }

    fun removeMessageListener(key: String) {
        messageListeners.remove(key)
    }

    fun addAckListener(key: String, listener: (String, Int) -> Unit) {
        ackListeners[key] = listener
    }

    fun removeAckListener(key: String) {
        ackListeners.remove(key)
    }

    // ✅ Set the persistent listener (called from MeshForegroundService)
    fun setPersistentMessageListener(listener: (String, String) -> Unit) {
        persistentMessageListener = listener
    }
}