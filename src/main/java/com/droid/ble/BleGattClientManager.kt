package com.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
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
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
@Suppress(
    "unused",
    "OverridingDeprecatedMember",
    "SpellCheckingInspection",
    "NounVerb",
    "UnusedEquals"
)
class BleGattClientManager(
    private val context: Context,
    private val onPeerConnected: (peerIdHex: String, deviceAddress: String, rssi: Int) -> Unit,
    private val onPeerDisconnected: (deviceAddress: String) -> Unit,
    private val onPacketReceived: (BlePacket, fromDeviceAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "BleGattClientManager"
        private const val SCAN_ON_DURATION_MS = 5000L
        private const val SCAN_OFF_DURATION_MS = 2000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    // Connection state
    private val connectedGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val addressToPeerId = ConcurrentHashMap<String, String>()
    private val seenAddresses = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()

    // Scanning state
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private var isScanCycleActive = false
    private var scanLeScanner: BluetoothLeScanner? = null

    // Write queues per device (FIFO)
    private val writeQueues = ConcurrentHashMap<String, LinkedList<BlePacket>>()
    private val writingInProgress = ConcurrentHashMap<String, Boolean>()

    // Scanning cycle control
    private val scanCycleRunnable = object : Runnable {
        override fun run() {
            if (!isScanCycleActive || adapter == null || !adapter.isEnabled) {
                return
            }

            val leScanner = scanLeScanner ?: run {
                Log.e(TAG, "BluetoothLeScanner is null, stopping cycle")
                stopScanning()
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
                        mainHandler.postDelayed({
                            if (isScanCycleActive) startScanning()
                        }, 2000)
                    }
                }
            }

            try {
                leScanner.startScan(listOf(filter), settings, scanCallback)
                isScanning = true
                Log.d(TAG, "BLE scanning started (duty cycle)")
            } catch (e: Exception) {
                Log.e(TAG, "startScan threw exception", e)
                mainHandler.postDelayed(this, SCAN_OFF_DURATION_MS + 1000)
                return
            }

            mainHandler.postDelayed({
                if (isScanCycleActive) {
                    try {
                        leScanner.stopScan(scanCallback)
                        isScanning = false
                        Log.d(TAG, "BLE scanning paused")
                    } catch (_: Exception) {}
                    mainHandler.postDelayed(this, SCAN_OFF_DURATION_MS)
                }
            }, SCAN_ON_DURATION_MS)
        }
    }

    fun startScanning() {
        if (isScanCycleActive) {
            Log.d(TAG, "Scanning cycle already active")
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

        scanLeScanner = adapter.bluetoothLeScanner
        if (scanLeScanner == null) {
            Log.e(TAG, "No BLE scanner available on this device – scanning disabled")
            return
        }

        isScanCycleActive = true
        mainHandler.post(scanCycleRunnable)
        Log.d(TAG, "Scanning duty cycle started")
    }

    fun stopScanning() {
        isScanCycleActive = false
        mainHandler.removeCallbacks(scanCycleRunnable)
        try {
            scanLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
        scanCallback = null

        connectedGatts.values.forEach { gatt ->
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
        }
        connectedGatts.clear()
        addressToPeerId.clear()
        seenAddresses.clear()
        pendingConnections.clear()
        writeQueues.clear()
        writingInProgress.clear()
        Log.d(TAG, "Stopped scanning and cleared all state")
    }

    fun connectedAddresses(): Set<String> = connectedGatts.keys

    fun writeTo(deviceAddress: String, packet: BlePacket): Boolean {
        val gatt = connectedGatts[deviceAddress] ?: return false

        val queue = writeQueues.getOrPut(deviceAddress) { LinkedList() }
        val isWriting = writingInProgress[deviceAddress] ?: false

        synchronized(queue) {
            if (isWriting) {
                queue.offer(packet)
                Log.d(TAG, "Queued packet for $deviceAddress (queue size: ${queue.size})")
                return true
            } else {
                return sendPacketNow(gatt, deviceAddress, packet)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendPacketNow(gatt: BluetoothGatt, deviceAddress: String, packet: BlePacket): Boolean {
        val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(BleConstants.CHARACTERISTIC_UUID) ?: return false
        val binaryData = packet.toBinary()

        return try {
            writingInProgress[deviceAddress] = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, binaryData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                char.value = binaryData
                gatt.writeCharacteristic(char)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeTo exception for $deviceAddress: ${e.message}", e)
            writingInProgress[deviceAddress] = false
            false
        }
    }

    private fun onWriteComplete(deviceAddress: String, success: Boolean) {
        val queue = writeQueues[deviceAddress] ?: return
        synchronized(queue) {
            writingInProgress[deviceAddress] = false
            if (queue.isEmpty()) return

            val nextPacket = queue.poll() ?: return
            val gatt = connectedGatts[deviceAddress]

            if (gatt == null) {
                queue.clear()
                writeQueues.remove(deviceAddress)
                writingInProgress.remove(deviceAddress)
                Log.w(TAG, "Device $deviceAddress disconnected, clearing write queue")
                return
            }

            if (!sendPacketNow(gatt, deviceAddress, nextPacket)) {
                Log.w(TAG, "Sending queued packet to $deviceAddress failed, dropping")
                onWriteComplete(deviceAddress, false)
            }
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
        writeQueues.remove(deviceAddress)
        writingInProgress.remove(deviceAddress)
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        Log.d(TAG, "📡 Scan result: $address (RSSI=${result.rssi})")

        if (address in seenAddresses || address in pendingConnections) {
            Log.d(TAG, "Already seen/pending, skipping")
            return
        }

        val peerIdBytes = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
            ?: run {
                Log.w(TAG, "No service data for $address – skipping")
                return
            }

        val peerId = peerIdBytes.toHex()
        Log.d(TAG, "🔗 Connecting to $address (peerId=$peerId)")
        seenAddresses.add(address)
        pendingConnections.add(address)

        connectTo(result.device, peerId, result.rssi)
    }

    private fun connectTo(device: BluetoothDevice, peerId: String, rssi: Int) {
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
                        writeQueues.remove(addr)
                        writingInProgress.remove(addr)
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

                mainHandler.post { onPeerConnected(peerId, device.address, rssi) }
                Log.d(TAG, "✅ Services discovered and notifications enabled for ${device.address}")
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                BlePacket.fromBinary(value)?.let { packet ->
                    mainHandler.post { onPacketReceived(packet, device.address) }
                }
            }

            @Suppress("OverridingDeprecatedMember")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                characteristic.value?.let { value ->
                    BlePacket.fromBinary(value)?.let { packet ->
                        mainHandler.post { onPacketReceived(packet, device.address) }
                    }
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val addr = device.address
                val success = status == BluetoothGatt.GATT_SUCCESS
                if (!success) {
                    Log.w(TAG, "Write to $addr failed with status $status")
                }
                onWriteComplete(addr, success)
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