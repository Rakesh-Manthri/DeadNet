package org.deadnet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class DeadNetPacketTest {

    @Test
    fun testPacketSerializationAndDeserialization() {
        val msgId = UUID.randomUUID()
        val senderPubKey = ByteArray(32) { (it + 1).toByte() }
        val recipientPubKey = ByteArray(32) { (it + 50).toByte() }
        val payload = "DeadNet Tactical Message Content".toByteArray(Charsets.UTF_8)
        val ttl = 1750000000L
        val hopCount = 2.toByte()
        val tokens = 5.toByte()

        val packet = DeadNetPacket(
            msgType = PacketType.SPRAY_AND_WAIT.code,
            msgId = msgId,
            senderPubKey = senderPubKey,
            recipientPubKey = recipientPubKey,
            ttl = ttl,
            hopCount = hopCount,
            tokensRemaining = tokens,
            payload = payload
        )

        val serialized = packet.toByteArray()
        assertEquals(DeadNetPacket.HEADER_SIZE + payload.size, serialized.size)

        // Verify magic bytes: 0xDE, 0xAD
        assertEquals(0xDE.toByte(), serialized[0])
        assertEquals(0xAD.toByte(), serialized[1])

        // Verify version: 0x01
        assertEquals(0x01.toByte(), serialized[2])

        // Verify msgType: 0x02
        assertEquals(PacketType.SPRAY_AND_WAIT.code, serialized[3])

        // Deserialize back
        val deserialized = DeadNetPacket.fromByteArray(serialized)
        assertEquals(packet.magic, deserialized.magic)
        assertEquals(packet.version, deserialized.version)
        assertEquals(packet.msgType, deserialized.msgType)
        assertEquals(packet.msgId, deserialized.msgId)
        assertArrayEquals(packet.senderPubKey, deserialized.senderPubKey)
        assertArrayEquals(packet.recipientPubKey, deserialized.recipientPubKey)
        assertEquals(packet.ttl, deserialized.ttl)
        assertEquals(packet.hopCount, deserialized.hopCount)
        assertEquals(packet.tokensRemaining, deserialized.tokensRemaining)
        assertArrayEquals(packet.payload, deserialized.payload)
        assertEquals(packet, deserialized)
    }

    @Test
    fun testStreamReadAndWrite() {
        val packet = DeadNetPacket(
            msgType = PacketType.DIRECT.code,
            msgId = UUID.randomUUID(),
            senderPubKey = ByteArray(32) { 1 },
            recipientPubKey = ByteArray(32) { 2 },
            ttl = 1800000000L,
            hopCount = 0,
            tokensRemaining = 1,
            payload = "Direct packet over stream".toByteArray(Charsets.UTF_8)
        )

        val outputStream = ByteArrayOutputStream()
        packet.writeTo(outputStream)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val parsed = DeadNetPacket.fromInputStream(inputStream)

        assertEquals(packet, parsed)
    }

    @Test
    fun testBroadcastDetection() {
        val broadcastPacket = DeadNetPacket(
            msgType = PacketType.SOS.code,
            msgId = UUID.randomUUID(),
            senderPubKey = ByteArray(32) { 10 },
            recipientPubKey = DeadNetPacket.BROADCAST_KEY,
            ttl = 1750000000L,
            hopCount = 0,
            tokensRemaining = 0,
            payload = "EMERGENCY SOS".toByteArray(Charsets.UTF_8)
        )

        assertTrue(broadcastPacket.isBroadcast)
        assertEquals(PacketType.SOS, broadcastPacket.packetType)

        val directPacket = broadcastPacket.copy(
            recipientPubKey = ByteArray(32) { 1 }
        )
        assertFalse(directPacket.isBroadcast)
    }

    @Test
    fun testInvalidMagicRejection() {
        val validPacket = DeadNetPacket(
            msgType = PacketType.DIRECT.code,
            msgId = UUID.randomUUID(),
            senderPubKey = ByteArray(32),
            recipientPubKey = ByteArray(32),
            ttl = 1000L,
            hopCount = 0,
            tokensRemaining = 1,
            payload = ByteArray(0)
        )
        val bytes = validPacket.toByteArray()
        bytes[0] = 0x00 // Corrupt magic

        try {
            DeadNetPacket.fromByteArray(bytes)
            fail("Expected IllegalArgumentException for corrupt magic")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid magic") == true)
        }
    }
}
