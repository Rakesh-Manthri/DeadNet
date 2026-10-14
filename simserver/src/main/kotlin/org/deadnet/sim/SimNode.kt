package org.deadnet.sim

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A virtual mesh node. Holds a 32-byte synthetic identity, a TCP "radio"
 * (a ServerSocket each peer dials into), and a receive buffer. Implements the
 * store-carry-forward Spray-and-Wait routing algorithm (L=5 token quota,
 * token splitting, handshake exchange, reverse ACK, msgId dedup) over real
 * localhost TCP sockets.
 */
class SimNode(
    val name: String,
    val listenPort: Int,
    val logger: (String) -> Unit = { println("[$name] $it") },
    /** Called when this node delivers a message; index is the node's slot in the mesh. */
    val onDelivered: (SimPacket, Int) -> Unit = { _, _ -> }
) {
    /** 32-byte synthetic identity (no real crypto — just a stable random blob). */
    val key: ByteArray = run {
        val bytes = ByteArray(SimPacket.PUBKEY_SIZE)
        java.security.SecureRandom().nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0x7F).toByte() // keep it non-broadcast
        bytes
    }

    /** Node id, derived exactly like DeadNetCrypto.deriveNodeId (8-char hex). */
    val nodeId: String = SimPacket.deriveNodeId(key)

    /** Index of this node in the SimServer mesh (used for REPL addressing). */
    var index: Int = -1

    /** All live TCP connections, keyed by remote nodeId. */
    private val peers = ConcurrentHashMap<String, Socket>()
    private val peerStreams = ConcurrentHashMap<String, OutputStream>()

    private val serverSocket: ServerSocket = ServerSocket(listenPort)
    private val running = AtomicBoolean(true)

    /** <msgId, carried packet> store for Spray-and-Wait carried messages. */
    private val store = ConcurrentHashMap<String, SimPacket>()

    /** msgIds this node has already processed (ingress dedup). */
    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** msgIds that have been delivered (or acked) — used for bookkeeping. */
    private val delivered = ConcurrentHashMap.newKeySet<String>()

    /** Logging ring buffer so `state` can show recent routing activity. */
    private val buffer = ConcurrentLinkedQueue<String>()

    init {
        log("Node ${nodeId} [$name] listening on 127.0.0.1:${listenPort}")
    }

    private fun log(msg: String) {
        logger("[$name/${nodeId}] $msg")
        buffer.offer("[$name/${nodeId}] $msg")
        while (buffer.size > 100) buffer.poll()
    }

    /** Start accepting connections on this node's TCP radio. */
    fun start() {
        Thread({
            while (running.get()) {
                try {
                    val client = serverSocket.accept()
                    Thread({ handleClient(client) }, "$name-accept").start()
                } catch (e: Exception) {
                    if (running.get()) log("Accept error: ${e.message}")
                }
            }
        }, "$name-listener").start()
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        try {
            while (running.get()) {
                val packet = try {
                    SimPacket.readFrom(input)
                } catch (e: EOFException) {
                    break
                } catch (_: Exception) {
                    break
                }
                ingress(packet, socket)
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Ingress pipeline — mirrors DtnRoutingEngine.handleIngressPacket:
     * handshake, dedup, reverse ack, deliver-for-me, store-carry-forward.
     */
    private fun ingress(packet: SimPacket, sourceSocket: Socket) {
        val msgIdStr = packet.msgId.toString()
        val senderNode = packet.senderNodeId()

        // 1. Handshake: register which socket this peer nodeId lives on.
        if (packet.msgType == SimPacketType.HANDSHAKE) {
            if (senderNode != nodeId) {
                val peerName = try { packet.payloadText().ifBlank { "Node $senderNode" } } catch (_: Exception) { "Node $senderNode" }
                val isNew = !peers.containsKey(senderNode)
                peers[senderNode] = sourceSocket
                peerStreams[senderNode] = sourceSocket.getOutputStream()
                log("Handshake from $peerName ($senderNode): link up on port ${sourceSocket.port}")

                if (isNew) {
                    sendHandshakeTo(senderNode)
                    syncStoredMessagesWithPeer(senderNode)
                }
            }
            return
        }

        // 2. Ingress dedup.
        if (!seen.add(msgIdStr)) {
            log("Dropping duplicate ${packet.typeName()} $msgIdStr")
            return
        }

        // 3. Reverse ACK.
        if (packet.msgType == SimPacketType.REVERSE_ACK) {
            if (delivered.add(msgIdStr)) {
                log("Recv Reverse ACK for $msgIdStr — message confirmed delivered")
                store.remove(msgIdStr)
            }
            return
        }

        // 4. Target recipient check.
        val isForMe = packet.recipientPubKey.contentEquals(key)
        if (isForMe) {
            val text = try { packet.payloadText() } catch (_: Exception) { "<binary>" }
            delivered.add(msgIdStr)
            store.remove(msgIdStr)
            log("DELIVERED msg ${msgIdStr.take(8)} from $senderNode > ${nodeId}: \"$text\" (hops=${packet.hopCount})")
            onDelivered.invoke(packet, index)

            // Emit Reverse ACK back along the link.
            val ack = SimPacket(
                msgType = SimPacketType.REVERSE_ACK,
                msgId = packet.msgId,
                senderPubKey = key,
                recipientPubKey = packet.senderPubKey,
                ttl = (System.currentTimeMillis() / 1000L) + 3600,
                hopCount = 0,
                tokensRemaining = 0,
                payload = ByteArray(0)
            )
            writeTo(senderNode, ack)
            return
        }

        // 5. Broadcast: flood to all other radios.
        if (packet.isBroadcast) {
            val text = try { packet.payloadText() } catch (_: Exception) { "<binary>" }
            log("Received broadcast $msgIdStr from $senderNode: \"$text\"")
            val forwardPacket = packet.copy(hopCount = (packet.hopCount + 1).toByte())
            for ((peerNodeId, _) in peers) {
                if (peerNodeId != senderNode) writeTo(peerNodeId, forwardPacket)
            }
            return
        }

        // 6. Store-Carry-Forward: store with its token quota.
        store[msgIdStr] = packet.copy(
            hopCount = (packet.hopCount + 1).coerceAtMost(255).toByte(),
            tokensRemaining = packet.tokensRemaining
        )
        log("Storing carried ${packet.typeName()} $msgIdStr for target ${packet.recipientNodeId()} (Tokens: ${packet.tokensRemaining.toInt() and 0xFF}, Hops: ${packet.hopCount + 1})")

        // Opportunistic Spray-and-Wait forward: relay to every other connected
        // peer (token-splitting in forwardTo). The real DtnRoutingEngine relays
        // carried messages to each peer it meets, which is what lets a message
        // hop through intermediate nodes like the N1->N2->N3 chain here.
        for (peerNodeId in peers.keys.toList()) {
            if (peerNodeId != senderNode) {
                forwardTo(peerNodeId, msgIdStr)
            }
        }
    }

    /** Create + store an outbound message and spray it to all connected radios. */
    fun sendMessage(recipientNodeId: String, text: String) {
        val recipientKey = SimServer.resolvePeerKey(recipientNodeId) ?: SimPacket.BROADCAST_KEY
        val msgId = UUID.randomUUID()
        val nowSec = System.currentTimeMillis() / 1000L
        val ttlSec = nowSec + 86400L // 24h, matches DtnRoutingEngine.DEFAULT_TTL_SECONDS

        val packet = SimPacket(
            msgType = SimPacketType.SPRAY_AND_WAIT,
            msgId = msgId,
            senderPubKey = key,
            recipientPubKey = recipientKey,
            ttl = ttlSec,
            hopCount = 0,
            tokensRemaining = DEFAULT_TOKEN_QUOTA,
            payload = text.toByteArray(Charsets.UTF_8)
        )

        store[msgId.toString()] = packet
        log("Dispatch msg ${msgId.toString().take(8)} to $recipientNodeId (tokens=${DEFAULT_TOKEN_QUOTA})")

        for (peerNodeId in peers.keys.toList()) {
            if (peerNodeId != nodeId) {
                writeTo(peerNodeId, packet)
            }
        }
    }

    /**
     * Token-splitting Spray-and-Wait forward to a specific peer. Matches
     * DtnRoutingEngine: peerTokens = current/2, local = current - peerTokens.
     */
    private fun forwardTo(peerNodeId: String, msgId: String) {
        val stored = store[msgId] ?: return
        // Never bounce a packet back to the peer we received it from. We DO
        // forward to the recipient when it is a directly-connected peer — that
        // is the final delivery leg (in the real engine this is the separate
        // "pending direct delivery" path; here it is folded into forwardTo).
        if (stored.senderNodeId() == peerNodeId) return

        val currentTokens = stored.tokensRemaining.toInt() and 0xFF
        if (currentTokens <= 1) return

        val peerTokens = currentTokens / 2
        val localTokens = currentTokens - peerTokens

        // Keep local copy with the local token share.
        store[msgId] = stored.copy(
            tokensRemaining = localTokens.toByte(),
            hopCount = (stored.hopCount + 1).coerceAtMost(255).toByte()
        )

        val forwardPacket = stored.copy(
            msgType = SimPacketType.SPRAY_AND_WAIT,
            hopCount = (stored.hopCount + 1).coerceAtMost(255).toByte(),
            tokensRemaining = peerTokens.toByte()
        )
        log("Relaying DTN packet ${msgId.take(8)} (split: $peerTokens to $peerNodeId, $localTokens local)")
        writeTo(peerNodeId, forwardPacket)
    }

    /** Opportunistically forward every stored carried message to a newly-linked peer. */
    private fun syncStoredMessagesWithPeer(peerNodeId: String) {
        for (msgId in store.keys.toList()) {
            forwardTo(peerNodeId, msgId)
        }
    }

    /** Send our HANDSHAKE over an established peer stream so the far side registers us. */
    private fun sendHandshakeTo(peerNodeId: String) {
        val raw = SimPacket(
            msgType = SimPacketType.HANDSHAKE,
            msgId = UUID.randomUUID(),
            senderPubKey = key,
            recipientPubKey = SimPacket.BROADCAST_KEY,
            ttl = (System.currentTimeMillis() / 1000L) + 3600,
            hopCount = 0,
            tokensRemaining = 0,
            payload = name.toByteArray(Charsets.UTF_8)
        )
        writeTo(peerNodeId, raw)
    }

    /**
     * Dial another node's TCP radio and register it as a peer. [remoteNodeIdHint]
     * is the node's id so we can key the socket immediately; handshakes complete
     * the two-way registration.
     */
    fun connectTo(remoteHost: String, remotePort: Int, remoteNodeIdHint: String) {
        try {
            val socket = Socket(remoteHost, remotePort)
            socket.tcpNoDelay = true
            peers[remoteNodeIdHint] = socket
            peerStreams[remoteNodeIdHint] = socket.getOutputStream()

            sendHandshakeTo(remoteNodeIdHint)
            log("Dialed $remoteNodeIdHint on $remoteHost:$remotePort; sent handshake")

            Thread({ handleClient(socket) }, "$name-client-${remoteNodeIdHint}").start()
        } catch (e: Exception) {
            log("Failed to connect to $remoteNodeIdHint: ${e.message}")
        }
    }

    private fun writeTo(peerNodeId: String, packet: SimPacket) {
        try {
            val out = peerStreams[peerNodeId] ?: peers[peerNodeId]?.getOutputStream() ?: return
            synchronized(out) {
                out.write(packet.toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            log("Write to $peerNodeId failed: ${e.message}")
            peers.remove(peerNodeId)
            peerStreams.remove(peerNodeId)
        }
    }

    /** Human-readable summary of this node's mesh + buffer state. */
    fun state(): String {
        val sb = StringBuilder()
        sb.append("$name ($index, ${nodeId}) @127.0.0.1:$listenPort\n")
        sb.append("  peers: ${peers.keys.joinToString(", ").ifEmpty { "(none)" }}\n")
        sb.append("  store (${store.size}): ")
        if (store.isEmpty()) sb.append("(empty)\n")
        else sb.append(store.values.joinToString("; ") { p ->
            "msg=${p.msgId.toString().take(8)} -> ${p.recipientNodeId()} tok=${p.tokensRemaining.toInt() and 0xFF} hop=${p.hopCount}"
        }).append("\n")
        sb.append("  delivered acks: ${delivered.size}\n")
        sb.append("  recent log:\n")
        buffer.forEach { sb.append("    $it\n") }
        return sb.toString()
    }

    fun stop() {
        running.set(false)
        try { serverSocket.close() } catch (_: Exception) {}
        peers.keys.toList().forEach { nid ->
            try { peers[nid]?.close() } catch (_: Exception) {}
        }
        peers.clear()
        peerStreams.clear()
    }

    companion object {
        const val DEFAULT_TOKEN_QUOTA: Byte = 5
    }
}
