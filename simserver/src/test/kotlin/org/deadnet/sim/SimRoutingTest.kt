package org.deadnet.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the DeadNet mesh simulation's packet framing and
 * Spray-and-Wait store-carry-forward routing.
 */
class SimRoutingTest {

    // ------------------------------------------------------------------
    // 1. Packet round-trip
    // ------------------------------------------------------------------

    @Test
    fun `packet round-trips through byte framing`() {
        val msgId = UUID.randomUUID()
        val sender = ByteArray(SimPacket.PUBKEY_SIZE) { it.toByte() }
        val recipient = ByteArray(SimPacket.PUBKEY_SIZE) { (it + 16).toByte() }
        val payload = "hello deadnet".toByteArray(Charsets.UTF_8)

        val original = SimPacket(
            msgType = SimPacketType.SPRAY_AND_WAIT,
            msgId = msgId,
            senderPubKey = sender,
            recipientPubKey = recipient,
            ttl = 1_700_000_000L,
            hopCount = 3,
            tokensRemaining = 5,
            payload = payload
        )

        val bytes = original.toByteArray()
        val decoded = SimPacket.fromByteArray(bytes)

        assertEquals(original, decoded)
        assertEquals(original.msgId, decoded.msgId)
        assertTrue(original.senderPubKey.contentEquals(decoded.senderPubKey))
        assertTrue(original.recipientPubKey.contentEquals(decoded.recipientPubKey))
        assertTrue(original.payload.contentEquals(decoded.payload))
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.tokensRemaining, decoded.tokensRemaining)
    }

    @Test
    fun `magic and payloadLength land at the expected header offsets`() {
        val payload = "abc".toByteArray(Charsets.UTF_8)
        val packet = SimPacket(
            msgType = SimPacketType.DIRECT,
            msgId = UUID.randomUUID(),
            senderPubKey = ByteArray(SimPacket.PUBKEY_SIZE) { 1 },
            recipientPubKey = ByteArray(SimPacket.PUBKEY_SIZE) { 2 },
            ttl = 123L,
            hopCount = 0,
            tokensRemaining = 0,
            payload = payload
        )

        val bytes = packet.toByteArray()

        assertEquals(SimPacket.HEADER_SIZE + payload.size, bytes.size)

        // magic 0xDEAD at bytes 0-1
        assertEquals(0xDE.toInt() and 0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xAD.toInt() and 0xFF, bytes[1].toInt() and 0xFF)

        // payloadLength (2 bytes, big-endian) at bytes 90-91
        val hi = bytes[90].toInt() and 0xFF
        val lo = bytes[91].toInt() and 0xFF
        assertEquals(payload.size, (hi shl 8) or lo)
    }

    // ------------------------------------------------------------------
    // 2. Spray-and-Wait token splitting arithmetic
    //    SimNode.forwardTo: peerTokens = current / 2, local = current - peerTokens
    // ------------------------------------------------------------------

    @Test
    fun `token split for quota five yields (2 to peer, 3 local)`() {
        val current = 5
        val peer = current / 2
        val local = current - peer
        assertEquals(2, peer)
        assertEquals(3, local)
        assertEquals(current, peer + local)
    }

    @Test
    fun `token split for quota three yields (1 to peer, 2 local)`() {
        val current = 3
        val peer = current / 2
        val local = current - peer
        assertEquals(1, peer)
        assertEquals(2, local)
        assertEquals(current, peer + local)
    }

    // ------------------------------------------------------------------
    // 3. End-to-end 3-node chain delivery (node1 -> node2 -> node3)
    // ------------------------------------------------------------------

    @Test
    fun `message sent on a three node chain is delivered to the far node`() {
        val basePort = 24000

        val delivered = AtomicReference<SimPacket?>()
        // SimNode binds its ServerSocket in the constructor, so a node on a given
        // port may be created only once. Create all three nodes a single time, and
        // give the third node the delivery hook that captures the delivered packet.
        val nodes = (0 until 3).map { i ->
            SimNode(
                name = "N${i + 1}",
                listenPort = basePort + i,
                logger = { _ -> },
                onDelivered = if (i == 2) { pkt, _ -> delivered.set(pkt) } else { _, _ -> }
            )
        }
        nodes.forEachIndexed { idx, n ->
            n.index = idx
            SimServerRegistry.register(idx, n)
            SimServer.nodeKeys[n.nodeId] = n.key
        }
        // Start the accept loops so each node can accept inbound TCP connections.
        nodes.forEach { it.start() }
        val node1 = nodes[0]
        val node2 = nodes[1]
        val node3 = nodes[2]

        try {
            // Wire the chain: N1<->N2 and N2<->N3 over real localhost sockets.
            node1.connectTo("127.0.0.1", node2.listenPort, node2.nodeId)
            node2.connectTo("127.0.0.1", node3.listenPort, node3.nodeId)

            // Give handshakes time to register both directions.
            Thread.sleep(500)

            // Inject a message from node1 to node3 (by 8-hex node id).
            node1.sendMessage(node3.nodeId, "end-to-end reachable")

            // Poll for delivery with a timeout.
            val deadline = System.currentTimeMillis() + 10_000
            while (delivered.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            val result = delivered.get()
            if (result == null) {
                fail(
                    "Message was not delivered to ${node3.nodeId} within the timeout.\n" +
                        "N1: ${node1.state()}\nN2: ${node2.state()}\nN3: ${node3.state()}"
                )
            }

            assertEquals("end-to-end reachable", result.payloadText())
            assertTrue(result.recipientPubKey.contentEquals(node3.key))
        } finally {
            node1.stop()
            node2.stop()
            node3.stop()
            SimServerRegistry.clear()
        }
    }
}
