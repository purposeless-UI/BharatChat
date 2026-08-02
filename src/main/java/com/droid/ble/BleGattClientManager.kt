package com.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.droid.crypto.toHex
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
@Suppress(
    "unused",
    "OverridingDeprecatedMember",
    "SpellCheckingInspection"
)
class BleGattClientManager(
    private val context: Context,
    private val onPeerConnected: (peerIdHex: String, deviceAddress: String) -> Unit,
    private val onPeerDisconnected: (deviceAddress: String) -> Unit,
    private val onPacketReceived: (BlePacket, fromDeviceAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "BleGattClientManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val connectedGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val addressToPeerId = ConcurrentHashMap<String, String>()
    private val seenAddresses = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()
    private var scanCallback: ScanCallback? = null
    private var isScanning = false

    fun startScanning() {
        if (isScanning) {
            Log.d(TAG, "Scanning already started")
            return
        }

        if (!BlePermissions.hasAll(context)) {
            Log.w(TAG, "Not scanning: missing BLE permissions")
            return
        }
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter available")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled")
            return
        }

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            Log.e(TAG, "No BLE scanner available on this device – scanning disabled")
            return
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "⚠️ Scan failed: $errorCode")
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    mainHandler.postDelayed({ startScanning() }, 2000)
                }
            }
        }

        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scanning started")
        } catch (e: Exception) {
            Log.e(TAG, "startScan threw exception", e)
        }
    }

    fun stopScanning() {
        isScanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        connectedGatts.values.forEach { gatt ->
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
        }
        connectedGatts.clear()
        addressToPeerId.clear()
        seenAddresses.clear()
        pendingConnections.clear()
        Log.d(TAG, "Stopped scanning and cleared all connections")
    }

    fun connectedAddresses(): Set<String> = connectedGatts.keys

    fun writeTo(deviceAddress: String, packet: BlePacket): Boolean {
        val gatt = connectedGatts[deviceAddress] ?: return false
        val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(BleConstants.CHARACTERISTIC_UUID) ?: return false
        val binaryData = packet.toBinary()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, binaryData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = binaryData
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeTo exception for $deviceAddress: ${e.message}", e)
            false
        }
    }

    fun disconnectPeer(deviceAddress: String) {
        val gatt = connectedGatts.remove(deviceAddress)
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            Log.d(TAG, "Disconnected peer: $deviceAddress")
        }
        addressToPeerId.remove(deviceAddress)
        seenAddresses.remove(deviceAddress)
        pendingConnections.remove(deviceAddress)
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        Log.d(TAG, "📡 Scan result: $address")

        if (address in seenAddresses || address in pendingConnections) {
            Log.d(TAG, "Already seen/pending, skipping")
            return
        }

        val peerIdBytes = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
        if (peerIdBytes == null) {
            Log.w(TAG, "No service data for $address – skipping")
            return
        }

        val peerId = peerIdBytes.toHex()
        Log.d(TAG, "🔗 Connecting to $address (peerId=$peerId)")
        seenAddresses.add(address)
        pendingConnections.add(address)
        connectTo(result.device, peerId)
    }

    private fun connectTo(device: BluetoothDevice, peerId: String) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Connection state change: status=$status, newState=$newState")

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "✅ Connected to ${device.address}")
                            try { gatt.requestMtu(517) } catch (_: Exception) { gatt.discoverServices() }
                        } else {
                            Log.e(TAG, "❌ Connection failed with status $status")
                            gatt.close()
                            pendingConnections.remove(device.address)
                            seenAddresses.remove(device.address)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "❌ Disconnected from ${device.address}")
                        val addr = device.address
                        connectedGatts.remove(addr)
                        addressToPeerId.remove(addr)
                        seenAddresses.remove(addr)
                        pendingConnections.remove(addr)
                        mainHandler.post { onPeerDisconnected(addr) }
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU changed: $mtu (status=$status)")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed for ${device.address}")
                    gatt.disconnect()
                    return
                }
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "Service UUID not found on ${device.address}")
                    gatt.disconnect()
                    return
                }
                val char = service.getCharacteristic(BleConstants.CHARACTERISTIC_UUID)
                if (char == null) {
                    Log.w(TAG, "Characteristic UUID not found on ${device.address}")
                    gatt.disconnect()
                    return
                }

                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(BleConstants.DESCRIPTOR_UUID)
                if (descriptor != null) {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }

                connectedGatts[device.address] = gatt
                addressToPeerId[device.address] = peerId
                pendingConnections.remove(device.address)
                mainHandler.post { onPeerConnected(peerId, device.address) }
                Log.d(TAG, "✅ Services discovered and notifications enabled for ${device.address}")
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                BlePacket.fromBinary(value)?.let { packet ->
                    mainHandler.post { onPacketReceived(packet, device.address) }
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                characteristic.value?.let { value ->
                    BlePacket.fromBinary(value)?.let { packet ->
                        mainHandler.post { onPacketReceived(packet, device.address) }
                    }
                }
            }
        }

        try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt threw for ${device.address}", e)
            pendingConnections.remove(device.address)
            seenAddresses.remove(device.address)
        }
    }
}