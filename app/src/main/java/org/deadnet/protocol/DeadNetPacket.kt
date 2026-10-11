package org.deadnet.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.UUID

/**
 * Unified Binary Packet Protocol representation for Project DeadNet.
 *
 * Header Layout (92 bytes):
 * - magic: 2 bytes (0xDEAD)
 * - version: 1 byte (0x01)
 * - msgType: 1 byte (0x01=Direct, 0x02=Spray-and-Wait, 0x03=Reverse ACK, 0xFF=SOS Broadcast)
 * - msgId: 16 bytes (UUID)
 * - senderPubKey: 32 bytes
 * - recipientPubKey: 32 bytes (or 32 zero-bytes for broadcast)
 * - ttl: 4 bytes (Unix epoch seconds)
 * - hopCount: 1 byte
 * - tokensRemaining: 1 byte (L quota for Spray-and-Wait)
 * - payloadLength: 2 bytes
 * - payload: Variable bytes (Ciphertext)
 */
data class DeadNetPacket(
    val magic: Short = MAGIC,
    val version: Byte = CURRENT_VERSION,
    val msgType: Byte,
    val msgId: UUID,
    val senderPubKey: ByteArray,
    val recipientPubKey: ByteArray,
    val ttl: Long, // Unix epoch seconds
    val hopCount: Byte,
    val tokensRemaining: Byte,
    val payload: ByteArray
) {
    init {
        require(senderPubKey.size == PUBKEY_SIZE) { "senderPubKey must be exactly $PUBKEY_SIZE bytes (got ${senderPubKey.size})" }
        require(recipientPubKey.size == PUBKEY_SIZE) { "recipientPubKey must be exactly $PUBKEY_SIZE bytes (got ${recipientPubKey.size})" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "payload exceeds max size of $MAX_PAYLOAD_SIZE bytes" }
    }

    val isBroadcast: Boolean
        get() = recipientPubKey.all { it == 0.toByte() }

    val packetType: PacketType?
        get() = PacketType.fromCode(msgType)

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.putShort(magic)
        buffer.put(version)
        buffer.put(msgType)
        buffer.putLong(msgId.mostSignificantBits)
        buffer.putLong(msgId.leastSignificantBits)
        buffer.put(senderPubKey)
        buffer.put(recipientPubKey)
        buffer.putInt((ttl and 0xFFFFFFFFL).toInt())
        buffer.put(hopCount)
        buffer.put(tokensRemaining)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)

        return buffer.array()
    }

    fun writeTo(outputStream: OutputStream) {
        val bytes = toByteArray()
        outputStream.write(bytes)
        outputStream.flush()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeadNetPacket

        if (magic != other.magic) return false
        if (version != other.version) return false
        if (msgType != other.msgType) return false
        if (msgId != other.msgId) return false
        if (!senderPubKey.contentEquals(other.senderPubKey)) return false
        if (!recipientPubKey.contentEquals(other.recipientPubKey)) return false
        if (ttl != other.ttl) return false
        if (hopCount != other.hopCount) return false
        if (tokensRemaining != other.tokensRemaining) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = magic.toInt()
        result = 31 * result + version
        result = 31 * result + msgType
        result = 31 * result + msgId.hashCode()
        result = 31 * result + senderPubKey.contentHashCode()
        result = 31 * result + recipientPubKey.contentHashCode()
        result = 31 * result + ttl.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + tokensRemaining
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC: Short = 0xDEAD.toShort() // -8531 as signed short
        const val CURRENT_VERSION: Byte = 0x01
        const val HEADER_SIZE: Int = 92
        const val PUBKEY_SIZE: Int = 32
        const val MAX_PAYLOAD_SIZE: Int = 65535

        val BROADCAST_KEY: ByteArray = ByteArray(PUBKEY_SIZE) { 0 }

        fun fromByteArray(bytes: ByteArray): DeadNetPacket {
            require(bytes.size >= HEADER_SIZE) {
                "Packet size (${bytes.size} bytes) is smaller than header size ($HEADER_SIZE bytes)"
            }

            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val magic = buffer.short
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid magic bytes: 0x${Integer.toHexString(magic.toInt() and 0xFFFF)}")
            }

            val version = buffer.get()
            if (version != CURRENT_VERSION) {
                throw IllegalArgumentException("Unsupported packet version: $version")
            }

            val msgType = buffer.get()
            val mostSig = buffer.long
            val leastSig = buffer.long
            val msgId = UUID(mostSig, leastSig)

            val senderPubKey = ByteArray(PUBKEY_SIZE)
            buffer.get(senderPubKey)

            val recipientPubKey = ByteArray(PUBKEY_SIZE)
            buffer.get(recipientPubKey)

            val ttlInt = buffer.int
            val ttl = ttlInt.toLong() and 0xFFFFFFFFL

            val hopCount = buffer.get()
            val tokensRemaining = buffer.get()
            val payloadLength = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < payloadLength) {
                throw IllegalArgumentException("Buffer underrun: expected $payloadLength payload bytes, got ${buffer.remaining()}")
            }

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            return DeadNetPacket(
                magic = magic,
                version = version,
                msgType = msgType,
                msgId = msgId,
                senderPubKey = senderPubKey,
                recipientPubKey = recipientPubKey,
                ttl = ttl,
                hopCount = hopCount,
                tokensRemaining = tokensRemaining,
                payload = payload
            )
        }

        fun fromInputStream(inputStream: InputStream): DeadNetPacket {
            val headerBuffer = ByteArray(HEADER_SIZE)
            readFully(inputStream, headerBuffer)

            val buffer = ByteBuffer.wrap(headerBuffer)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val magic = buffer.short
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid magic bytes: 0x${Integer.toHexString(magic.toInt() and 0xFFFF)}")
            }

            val version = buffer.get()
            val msgType = buffer.get()
            val mostSig = buffer.long
            val leastSig = buffer.long
            val msgId = UUID(mostSig, leastSig)

            val senderPubKey = ByteArray(PUBKEY_SIZE)
            buffer.get(senderPubKey)

            val recipientPubKey = ByteArray(PUBKEY_SIZE)
            buffer.get(recipientPubKey)

            val ttlInt = buffer.int
            val ttl = ttlInt.toLong() and 0xFFFFFFFFL

            val hopCount = buffer.get()
            val tokensRemaining = buffer.get()
            val payloadLength = buffer.short.toInt() and 0xFFFF

            val payload = ByteArray(payloadLength)
            if (payloadLength > 0) {
                readFully(inputStream, payload)
            }

            return DeadNetPacket(
                magic = magic,
                version = version,
                msgType = msgType,
                msgId = msgId,
                senderPubKey = senderPubKey,
                recipientPubKey = recipientPubKey,
                ttl = ttl,
                hopCount = hopCount,
                tokensRemaining = tokensRemaining,
                payload = payload
            )
        }

        private fun readFully(inputStream: InputStream, destination: ByteArray) {
            var bytesReadTotal = 0
            while (bytesReadTotal < destination.size) {
                val read = inputStream.read(destination, bytesReadTotal, destination.size - bytesReadTotal)
                if (read == -1) {
                    throw java.io.EOFException("Premature EOF while reading ${destination.size} bytes (read $bytesReadTotal)")
                }
                bytesReadTotal += read
            }
        }
    }
}
