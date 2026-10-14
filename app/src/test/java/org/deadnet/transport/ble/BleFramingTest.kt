package org.deadnet.transport.ble

import org.deadnet.protocol.DeadNetPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Unit tests for the BLE chunking/reassembly framing that mirrors
 * BleTransport.handleIncomingBytes: hold bytes in a buffer, wait until
 * >= HEADER_SIZE, read the 2-byte big-endian payloadLength at offset 90,
 * slice out [0, HEADER_SIZE+len), and preserve any trailing bytes.
 *
 * BleTransport itself cannot be instantiated on the JVM (Android deps), so
 * this test uses a faithful test-local ReassemblyBuffer to pin the framing
 * arithmetic and edge cases.
 */
class BleFramingTest {

    private class ReassemblyBuffer {
        private val buffer = ByteArrayOutputStream()

        /** Feed a chunk; returns any complete packets assembled so far. */
        fun ingest(chunk: ByteArray): List<ByteArray> {
            buffer.write(chunk)
            return reassembleIfPossible()
        }

        val hasPartialData: Boolean
            get() = buffer.size() > 0

        private fun reassembleIfPossible(): List<ByteArray> {
            val assembled = mutableListOf<ByteArray>()
            while (true) {
                val currentBytes = buffer.toByteArray()
                if (currentBytes.size < DeadNetPacket.HEADER_SIZE) break

                val payloadLen =
                    ByteBuffer.wrap(currentBytes, 90, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                val fullPacketSize = DeadNetPacket.HEADER_SIZE + payloadLen

                if (currentBytes.size < fullPacketSize) break

                val packetBytes = currentBytes.copyOfRange(0, fullPacketSize)
                assembled.add(packetBytes)

                buffer.reset()
                if (currentBytes.size > fullPacketSize) {
                    buffer.write(currentBytes.copyOfRange(fullPacketSize, currentBytes.size))
                }
            }
            return assembled
        }
    }

    private fun randomPubKey(seed: Int) = ByteArray(DeadNetPacket.PUBKEY_SIZE) { (seed + it).toByte() }

    private fun buildPacket(payloadSeed: Int, payloadSize: Int): DeadNetPacket =
        DeadNetPacket(
            msgType = 1,
            msgId = UUID.randomUUID(),
            senderPubKey = randomPubKey(payloadSeed),
            recipientPubKey = randomPubKey(payloadSeed + 10),
            ttl = System.currentTimeMillis() / 1000,
            hopCount = 1,
            tokensRemaining = 5,
            payload = ByteArray(payloadSize) { (payloadSeed + it).toByte() }
        )

    @Test
    fun reassemblesPacketSplitAcrossArbitraryChunkSizes() {
        val original = buildPacket(payloadSeed = 1, payloadSize = 4096)
        val packetBytes = original.toByteArray()
        assertEquals(DeadNetPacket.HEADER_SIZE + 4096, packetBytes.size)

        val reassembly = ReassemblyBuffer()
        val assembled = mutableListOf<ByteArray>()

        val chunkSizes = intArrayOf(7, 13, 180, 500)
        var offset = 0
        var idx = 0
        while (offset < packetBytes.size) {
            val size = chunkSizes[idx % chunkSizes.size]
            val end = (offset + size).coerceAtMost(packetBytes.size)
            assembled.addAll(reassembly.ingest(packetBytes.copyOfRange(offset, end)))
            offset = end
            idx++
        }

        assertEquals(1, assembled.size)
        assertArrayEquals(packetBytes, assembled[0])

        assertEquals(original, DeadNetPacket.fromByteArray(assembled[0]))
    }

    @Test
    fun partialHeaderDoesNotPrematurelyReassemble() {
        val original = buildPacket(payloadSeed = 2, payloadSize = 128)
        val packetBytes = original.toByteArray()

        val reassembly = ReassemblyBuffer()

        // Only 3 bytes — far short of the 92-byte header.
        assertEquals(0, reassembly.ingest(packetBytes.copyOfRange(0, 3)).size)
        assertEquals(true, reassembly.hasPartialData)

        // 40 bytes — still < HEADER_SIZE.
        assertEquals(0, reassembly.ingest(packetBytes.copyOfRange(3, 43)).size)
        assertEquals(true, reassembly.hasPartialData)

        // Rest completes the packet.
        val emitted = reassembly.ingest(packetBytes.copyOfRange(43, packetBytes.size))
        assertEquals(1, emitted.size)
        assertArrayEquals(packetBytes, emitted[0])
    }

    @Test
    fun multipleBackToBackPacketsReassembleIndependently() {
        val first = buildPacket(payloadSeed = 3, payloadSize = 512)
        val second = buildPacket(payloadSeed = 4, payloadSize = 768)
        assertEquals(true, first.msgId != second.msgId)

        val firstBytes = first.toByteArray()
        val secondBytes = second.toByteArray()
        val combined = firstBytes + secondBytes

        val oddChunkSizes = intArrayOf(11, 29, 73, 155)
        val reassembly = ReassemblyBuffer()
        val assembled = mutableListOf<ByteArray>()

        var offset = 0
        var idx = 0
        while (offset < combined.size) {
            val size = oddChunkSizes[idx % oddChunkSizes.size]
            val end = (offset + size).coerceAtMost(combined.size)
            assembled.addAll(reassembly.ingest(combined.copyOfRange(offset, end)))
            offset = end
            idx++
        }

        assertEquals(2, assembled.size)
        assertArrayEquals(firstBytes, assembled[0])
        assertArrayEquals(secondBytes, assembled[1])

        assertEquals(first, DeadNetPacket.fromByteArray(assembled[0]))
        assertEquals(second, DeadNetPacket.fromByteArray(assembled[1]))
    }
}
