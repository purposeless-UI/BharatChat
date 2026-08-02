package com.droid.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.droid.crypto.hexToBytes
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
@Suppress("SpellCheckingInspection")
class BleGattServerManager(
    private val context: Context,
    private val myPeerIdHex: String,
    private val onPacketReceived: (BlePacket, fromDeviceAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "BleGattServerManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser = adapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private var isActive = false
    private var advertisingRetryCount = 0
    private var isAdvertising = false

    fun start() {
        if (!BlePermissions.hasAll(context) || isActive) return
        isActive = true
        setupGattServer()
        startAdvertising()
    }

    fun stop() {
        isActive = false
        isAdvertising = false
        advertisingRetryCount = 0
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        connectedDevices.clear()
        Log.d(TAG, "Server stopped")
    }

    fun connectedAddresses(): Set<String> = connectedDevices.keys

    fun notify(deviceAddress: String, packet: BlePacket): Boolean {
        val device = connectedDevices[deviceAddress] ?: return false
        val char = characteristic ?: return false
        val binaryData = packet.toBinary()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, binaryData) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = binaryData
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "notify exception", e)
            false
        }
    }

    private fun setupGattServer() {
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                Log.d(TAG, "Server connection state: ${device.address} status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices[device.address] = device
                    Log.d(TAG, "Central connected: ${device.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device.address)
                    Log.d(TAG, "Central disconnected: ${device.address}")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (characteristic.uuid == BleConstants.CHARACTERISTIC_UUID) {
                    BlePacket.fromBinary(value)?.let { packet ->
                        mainHandler.post { onPacketReceived(packet, device.address) }
                    }
                }
                if (responseNeeded) {
                    try { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) } catch (_: Exception) {}
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) {
                    try { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) } catch (_: Exception) {}
                }
            }
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)

        characteristic = BluetoothGattCharacteristic(
            BleConstants.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val descriptor = BluetoothGattDescriptor(
            BleConstants.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)

        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
        Log.d(TAG, "GATT server set up")
    }

    @Suppress("DEPRECATION")
    private fun startAdvertising(retryCount: Int = 0) {
        if (!BlePermissions.hasAll(context) || adapter == null || advertiser == null) return
        if (adapter.isMultipleAdvertisementSupported == false) {
            Log.w(TAG, "Multiple advertisement not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), myPeerIdHex.hexToBytes())
            .setIncludeTxPowerLevel(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "✅ Advertising started successfully")
                isAdvertising = true
                advertisingRetryCount = 0
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "❌ Advertising failed: $errorCode (attempt $retryCount)")
                isAdvertising = false
                if (retryCount < 5) {
                    val delay = 2000L * (retryCount + 1)
                    mainHandler.postDelayed({
                        startAdvertising(retryCount + 1)
                    }, delay)
                    Log.d(TAG, "Will retry advertising in ${delay}ms")
                } else {
                    Log.e(TAG, "Advertising failed after 5 attempts")
                }
            }
        }

        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.d(TAG, "Advertising start called")
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertising threw", e)
            if (retryCount < 5) {
                mainHandler.postDelayed({
                    startAdvertising(retryCount + 1)
                }, 2000L * (retryCount + 1))
            }
        }
    }
}