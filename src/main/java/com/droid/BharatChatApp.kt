package com.droid

import android.app.Application
import android.util.Log
import com.droid.bharatchat.UserProfileManager
import com.droid.mesh.BluetoothMeshService
import com.droid.mesh.MeshPacket
import com.droid.mesh.MeshPacketRouter

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "BharatChat application initialized successfully.")

        // Initialize global packet router instance
        globalPacketRouter = MeshPacketRouter(
            currentUserId = UserProfileManager.getMyUsername(this),
            onMessageReadyToDeliver = { packet ->
                Log.d(TAG, "Global mesh packet received for delivery: ${packet.messageId}")
            },
            onPacketRelay = { relayedPacket ->
                globalBluetoothMeshService.transmitPacket(relayedPacket.serialize())
            }
        )

        // Initialize global continuous background Bluetooth mesh service
        globalBluetoothMeshService = BluetoothMeshService(
            context = this,
            onPacketReceived = { rawBytes ->
                val packet = MeshPacket.deserialize(rawBytes)
                if (packet != null) {
                    globalPacketRouter.handleIncomingPacket(packet)
                }
            }
        )

        // Start background advertising and scanning globally across the app lifecycle
        globalBluetoothMeshService.startAdvertising()
        globalBluetoothMeshService.startScanning()
    }

    override fun onTerminate() {
        super.onTerminate()
        globalBluetoothMeshService.stopAll()
    }
}