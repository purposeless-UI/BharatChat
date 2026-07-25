package com.droid.mesh

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
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

    /**
     * Inspects a raw BLE scan result. If it broadcasts our application service UUID
     * and carries a matching contact presence tag, it returns the verified device address.
     */
    fun processScanResult(result: ScanResult): String? {
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
            // Read custom byte payloads if embedded in manufacturer fields
            for (i in 0 until manufacturerData.size()) {
                val companyId = manufacturerData.keyAt(i)
                val dataBytes = manufacturerData.get(companyId)
                if (dataBytes != null && dataBytes.isNotEmpty()) {
                    val scannedTagString = android.util.Base64.encodeToString(dataBytes, android.util.Base64.NO_WRAP)
                    
                    // 3. Match against trusted local contacts
                    val knownSecrets = getKnownContactSecrets()
                    if (PresenceTagEngine.matchesAnyContact(scannedTagString, knownSecrets)) {
                        Log.d(TAG, "Successfully verified paired contact at address: ${result.device.address}")
                        return result.device.address
                    }
                }
            }
        }

        return null
    }
}