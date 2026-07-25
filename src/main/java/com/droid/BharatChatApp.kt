package com.droid

import android.app.Application
import android.util.Log
import com.droid.bharatchat.UserProfileManager
import com.droid.mesh.BluetoothMeshService
import com.droid.mesh.MeshPacket
import com.droid.mesh.MeshPacketRouter
import com.droid.storage.ContactStorageManager

class BharatChatApp : Application() {

    companion object {
        private const val TAG = "BharatChatApp"
        lateinit var instance: BharatChatApp
            private set
    }

    lateinit var globalBluetoothMeshService: BluetoothMeshService
        private set

    lateinit var globalPacketRouter: MeshPacketRouter
        private set

    private lateinit var contactStorageManager: ContactStorageManager

    // Set by ChatActivity while it is the foreground screen for a given peer, so
    // incoming packets can be pushed straight into the open chat UI. When null
    // (no chat screen open, or a different peer is open) messages are still saved
    // to that peer's history so they aren't lost - onResume() of ChatActivity will
    // pick them up from storage.
    @Volatile
    var activeMessageListener: ((MeshPacket) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "BharatChat application initialized successfully.")

        contactStorageManager = ContactStorageManager(this)

        // Initialize global packet router instance
        globalPacketRouter = MeshPacketRouter(
            currentUserId = UserProfileManager.getMyUsername(this),
            onMessageReadyToDeliver = { packet ->
                Log.d(TAG, "Global mesh packet received for delivery: ${packet.messageId}")
                val logEntry = "[${packet.senderId}]: ${packet.encryptedPayload}  [\u2713\u2713 Received]"
                contactStorageManager.saveMessageToPeerHistory(packet.senderId, logEntry)
                // Forward live to whichever ChatActivity screen is currently open, if any.
                activeMessageListener?.invoke(packet)
            },
            onPacketRelay = { relayedPacket ->
                globalBluetoothMeshService.transmitPacket(relayedPacket.serialize())
            }
        )

        // Initialize global continuous background Bluetooth mesh service. This is the
        // ONLY BluetoothMeshService instance in the whole app - every screen (Main,
        // Pairing, Chat) shares it via BharatChatApp.instance.globalBluetoothMeshService
        // instead of each creating its own. Previously each Activity created a brand new
        // BluetoothMeshService(this), which meant peers discovered on one screen were
        // invisible on another (e.g. "Launch Matrix Chat" on the home screen always said
        // "No Active Peers Found" even right after a successful QR pairing), and multiple
        // simultaneous BLE advertisers on the same device fought each other, breaking
        // message transmission.
        globalBluetoothMeshService = BluetoothMeshService(
            context = this,
            getMyCurrentSecret = { UserProfileManager.getMyIdentitySecret(this) },
            getKnownContactSecrets = { contactStorageManager.getTrustedContactSecrets() },
            onPacketReceived = { rawBytes ->
                val packet = MeshPacket.deserialize(rawBytes)
                if (packet != null) {
                    globalPacketRouter.handleIncomingPacket(packet)
                }
            }
        )

        // Start background advertising and scanning globally across the app lifecycle.
        // Note: at process start, BLE runtime permissions may not be granted yet - that's
        // fine, both calls no-op safely and MainActivity re-invokes them once permissions
        // are granted.
        globalBluetoothMeshService.startAdvertising()
        globalBluetoothMeshService.startScanning()
    }

    override fun onTerminate() {
        super.onTerminate()
        globalBluetoothMeshService.stopAll()
    }
}
