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
    val statusText: String = "AVAILABLE",
    val publicKey: ByteArray = ByteArray(0)
) {
    val nodeId: String
        get() = id

    val hasPublicKey: Boolean
        get() = publicKey.size == 32

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerNode
        return id == other.id &&
                name == other.name &&
                transportType == other.transportType &&
                rssi == other.rssi &&
                address == other.address &&
                isGroupOwner == other.isGroupOwner &&
                isConnected == other.isConnected &&
                statusText == other.statusText &&
                publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + transportType.hashCode()
        result = 31 * result + rssi
        result = 31 * result + address.hashCode()
        result = 31 * result + isGroupOwner.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + statusText.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
