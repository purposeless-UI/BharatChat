package com.droid.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("B7A1C2D3-4E5F-4A6B-9C7D-8E1F2A3B4C5D")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("C8B2D3E4-5F60-4B7C-AD8E-9F2A3B4C5D6E")
    val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TTL_INITIAL = 7

    // Packet types
    const val TYPE_DATA = 0x02
    const val TYPE_DELIVERY_ACK = 0x03
    const val TYPE_READ_ACK = 0x04
    const val TYPE_DATA_FRAGMENT = 0x05
    const val TYPE_HELLO = 0x06   // ✅ Added for B.A.T.M.A.N. routing

    // Maximum payload size for a single BLE packet (safe for typical MTU)
    const val MAX_FRAGMENT_PAYLOAD_SIZE = 800
    const val TTL_ACK = 7   // Allows ACKs to travel back through multiple hops
}