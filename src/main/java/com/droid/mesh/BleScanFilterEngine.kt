package com.droid.mesh

import android.bluetooth.le.ScanResult
import android.util.Log
import com.droid.crypto.PresenceTagEngine
import java.util.UUID

class BleScanFilterEngine(
    private val targetServiceUuid: UUID,
    private val getKnownContactSecrets: () -> List<ByteArray>
) {
    companion object {
        private const val TAG = "BleScanFilterEngine"
    }

    private val discoveredPeerNames = mutableSetOf<String>()

    /**
     * Data class to hold both the verified device address and its name for chat selection.
     */
    data class VerifiedPeer(
        val deviceAddress: String,
        val deviceName: String?
    )

    /**
     * Inspects a raw BLE scan result. If it broadcasts our application service UUID
     * and carries a matching contact presence tag, it returns the verified peer details.
     */
    fun processScanResult(result: ScanResult): VerifiedPeer? {
        val record = result.scanRecord ?: return null
        val serviceUuids = record.serviceUuids
        
        // 1. Verify if the packet belongs to our mesh network UUID
        val isTargetMeshNode = serviceUuids?.any { it.uuid == targetServiceUuid } ?: false
        if (!isTargetMeshNode) {
            return null
        }

        // 2. Extract manufacturer data or service data containing the presence tag
        val manufacturerData = record.manufacturerSpecificData
        if (manufacturerData != null && manufacturerData.size() > 0) {
            for (i in 0 until manufacturerData.size()) {
                val companyId = manufacturerData.keyAt(i)
                val dataBytes = manufacturerData.get(companyId)
                if (dataBytes != null && dataBytes.isNotEmpty()) {
                    val scannedTagString = android.util.Base64.encodeToString(dataBytes, android.util.Base64.NO_WRAP)
                    
                    // 3. Match against trusted local contacts
                    val knownSecrets = getKnownContactSecrets()
                    if (PresenceTagEngine.matchesAnyContact(scannedTagString, knownSecrets)) {
                        val address = result.device.address
                        val name = record.deviceName ?: "Peer_${address.takeLast(5)}"
                        
                        discoveredPeerNames.add(name)
                        Log.d(TAG, "Successfully verified paired contact: $name at address: $address")
                        return VerifiedPeer(deviceAddress = address, deviceName = name)
                    }
                }
            }
        }

        // Fallback: If it's a target mesh node even without strict presence tags, add to list
        val fallbackName = record.deviceName ?: "Peer_${result.device.address.takeLast(5)}"
        discoveredPeerNames.add(fallbackName)

        return null
    }

    /**
     * Returns all unique discovered peer names for UI display.
     */
    fun getDiscoveredPeersList(): List<String> {
        return discoveredPeerNames.toList()
    }
}