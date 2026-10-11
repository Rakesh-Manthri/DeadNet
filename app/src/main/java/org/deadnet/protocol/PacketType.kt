package org.deadnet.protocol

enum class PacketType(val code: Byte) {
    DIRECT(0x01.toByte()),
    SPRAY_AND_WAIT(0x02.toByte()),
    REVERSE_ACK(0x03.toByte()),
    HANDSHAKE(0x04.toByte()),
    FILE_METADATA(0x05.toByte()),
    FILE_CHUNK(0x06.toByte());

    companion object {
        fun fromCode(code: Byte): PacketType? {
            return entries.firstOrNull { it.code == code }
        }
    }
}
