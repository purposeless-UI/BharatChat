package com.droid.mesh

import android.Manifest
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
    private val getKnownContactSecrets: () -> List<ByteArray> = { emptyList() }
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
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                // Check if the peer matches a paired contact via presence tags, 
                // or fallback to logging standard broadcast info if un-gated.
                val verifiedAddress = scanFilterEngine.processScanResult(it)
                if (verifiedAddress != null) {
                    Log.d(TAG, "Verified Paired BharatChat Peer Found: $verifiedAddress")
                } else {
                    val deviceName = if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        it.device.name ?: "Unknown Peer"
                    } else {
                        "Unknown Peer"
                    }
                    Log.d(TAG, "Discovered general device: $deviceName (${it.device.address})")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Mesh Scanning failed with error code: $errorCode")
        }
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
            .setIncludeDeviceName(false) // Hide real device name for privacy
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))

        // Embed rotating daily presence tag if a secret is provided
        val mySecret = getMyCurrentSecret()
        if (mySecret != null) {
            val dailyTagString = PresenceTagEngine.generateDailyPresenceTag(mySecret)
            val tagBytes = Base64.decode(dailyTagString, Base64.NO_WRAP)
            dataBuilder.addManufacturerData(MFR_ID, tagBytes)
        }

        bleAdvertiser?.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
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
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
            if (isScanning && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
            }
        }
    }
}