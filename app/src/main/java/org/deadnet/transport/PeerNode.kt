package org.deadnet.transport

enum class TransportType {
    WIFI_DIRECT,
    BLE,
    ULTRASONIC
}

data class PeerNode(
    val id: String, // Truncated Node ID or MAC identifier
    val name: String,
    val transportType: TransportType,
    val rssi: Int = 0, // In dBm (or dB for audio)
    val lastSeen: Long = System.currentTimeMillis(),
    val address: String = "",
    val isGroupOwner: Boolean = false,
    val isConnected: Boolean = false,
    val statusText: String = "AVAILABLE"
)
