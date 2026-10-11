package org.deadnet.transport.ble

import java.util.UUID

/**
 * UUID definitions and protocol constants for DeadNet BLE Ambient Transport.
 */
object BleConstants {
    // 128-bit DeadNet Service UUID
    val SERVICE_UUID: UUID = UUID.fromString("0000DEAD-0000-1000-8000-00805F9B34FB")

    // Characteristic for transmitting/receiving DeadNet packets (Write Without Response / Notify)
    val PACKET_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000DEAD-0001-1000-8000-00805F9B34FB")

    // Client Characteristic Configuration Descriptor (for notifications)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9b34fb")

    // 16-bit Service Data ID used in compact BLE Advertising packets (0xDEAD)
    const val SERVICE_DATA_UUID_16: Int = 0xDEAD

    // BLE MTU target and chunk size (standard BLE 4.2/5.0 payload size)
    const val DEFAULT_BLE_CHUNK_SIZE: Int = 180
}
