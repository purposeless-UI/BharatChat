package com.droid.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.droid.crypto.PresenceTagEngine
import java.util.UUID

class BluetoothMeshService(
    private val context: Context,
    private val getMyCurrentSecret: () -> ByteArray? = { null },
    private val getKnownContactSecrets: () -> List<ByteArray> = { emptyList() },
    private val onPacketReceived: ((ByteArray) -> Unit)? = null
) {
    companion object {
        private const val TAG = "BluetoothMeshService"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FE42-0000-1000-8000-00805F9B34FB")
        private const val MFR_ID = 0xFFFF // Custom manufacturer ID for BharatChat presence tags
    }

    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    private val bleScanner = bluetoothAdapter?.bluetoothLeScanner

    private var isAdvertising = false
    private var isScanning = false
    private val scanFilterEngine = BleScanFilterEngine(MESH_SERVICE_UUID, getKnownContactSecrets)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "BLE Mesh Advertising started successfully.")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "BLE Mesh Advertising failed with error code: $errorCode")
            isAdvertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                val verifiedPeer = scanFilterEngine.processScanResult(scanResult)
                if (verifiedPeer != null) {
                    Log.d(TAG, "Verified Paired BharatChat Peer Found: ${verifiedPeer.deviceName} at ${verifiedPeer.deviceAddress}")
                } else {
                    val deviceName = if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        scanResult.device.name ?: "Unknown Peer"
                    } else {
                        "Unknown Peer"
                    }
                    Log.d(TAG, "Discovered general device: $deviceName (${scanResult.device.address})")
                }

                scanResult.scanRecord?.getManufacturerSpecificData(MFR_ID)?.let { payloadBytes ->
                    if (payloadBytes.isNotEmpty()) {
                        onPacketReceived?.invoke(payloadBytes)
                    }
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Mesh Scanning failed with error code: $errorCode")
        }
    }

    fun getDiscoveredPeerNames(): List<String> {
        return scanFilterEngine.getDiscoveredPeersList()
    }

    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or unavailable. Cannot advertise.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))

        val mySecret = getMyCurrentSecret()
        if (mySecret != null) {
            val dailyTagString = PresenceTagEngine.generateDailyPresenceTag(mySecret)
            val tagBytes = Base64.decode(dailyTagString, Base64.NO_WRAP)
            dataBuilder.addManufacturerData(MFR_ID, tagBytes)
        }

        bleAdvertiser?.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun transmitPacket(packetBytes: ByteArray): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled. Cannot transmit packet.")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission for transmission.")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(500)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addManufacturerData(MFR_ID, packetBytes)
            .build()

        bleAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                android.os.Handler(context.mainLooper).postDelayed({
                    try {
                        bleAdvertiser.stopAdvertising(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to stop broadcast burst", e)
                    }
                }, 400)
            }
            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.e(TAG, "Packet broadcast advertisement failed: $errorCode")
            }
        })

        return true
    }

    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled or unavailable. Cannot scan.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission.")
            return
        }

        Log.d(TAG, "Scanning for nearby BharatChat mesh peers...")
        bleScanner?.startScan(scanCallback)
        isScanning = true
    }

    fun stopAll() {
        if (bluetoothAdapter?.isEnabled == true) {
            if (isAdvertising && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    bleAdvertiser?.stopAdvertising(advertiseCallback)
                } catch (_: Exception) {}
                isAdvertising = false
            }
            if (isScanning && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                try {
                    bleScanner?.stopScan(scanCallback)
                } catch (_: Exception) {}
                isScanning = false
            }
        }
    }
}