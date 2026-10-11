package org.deadnet.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.deadnet.crypto.DeadNetCrypto
import org.deadnet.crypto.DeadNetCrypto.toHexString
import org.deadnet.data.DeadNetDatabase
import org.deadnet.data.DeadNetMessage
import org.deadnet.data.SeenPacket
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.protocol.PacketType
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportLayer
import java.util.UUID

/**
 * Delay-Tolerant Mesh Routing Engine implementing:
 * - Store-Carry-Forward Spray-and-Wait routing (L=5 quota)
 * - Ingress packet deduplication via SeenPacket table
 * - Reverse ACK processing
 * - SOS emergency multi-transport blast
 * - Queue Eviction Daemon (10-minute periodic TTL sweep)
 */
class DtnRoutingEngine(
    private val database: DeadNetDatabase,
    private val ownKeyPair: DeadNetCrypto.KeyPair,
    private val scope: CoroutineScope
) {
    companion object {
        const val DEFAULT_TOKEN_QUOTA = 5
        const val DEFAULT_TTL_SECONDS = 86400L // 24 hours
        const val EVICTION_INTERVAL_MS = 600000L // 10 minutes
    }

    private val transports = mutableListOf<TransportLayer>()
    private var evictionJob: Job? = null

    // Event sinks
    private val _sosAlertFlow = MutableSharedFlow<DeadNetPacket>(extraBufferCapacity = 16)
    val sosAlertFlow: SharedFlow<DeadNetPacket> = _sosAlertFlow.asSharedFlow()

    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()

    fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        val entry = "[$timestamp] $message"
        _logFlow.value = (_logFlow.value + entry).takeLast(200)
    }

    fun registerTransport(transport: TransportLayer) {
        transports.add(transport)
        transport.onPacketReceived = { packet ->
            scope.launch(Dispatchers.IO) {
                handleIngressPacket(packet, transport)
            }
        }
        transport.onLogEvent = { logMsg ->
            log(logMsg)
        }
    }

    fun start() {
        log("Starting DTN Routing Engine (Node: ${ownKeyPair.nodeId})...")
        transports.forEach { it.start() }
        startEvictionDaemon()
    }

    fun stop() {
        log("Stopping DTN Routing Engine...")
        evictionJob?.cancel()
        evictionJob = null
        transports.forEach { it.stop() }
    }

    /**
     * Ingress pipeline processing for packets received across any transport interface.
     */
    suspend fun handleIngressPacket(packet: DeadNetPacket, sourceTransport: TransportLayer) {
        val msgIdStr = packet.msgId.toString()
        log("Ingress packet: $msgIdStr (type ${packet.packetType}, hops=${packet.hopCount}, tokens=${packet.tokensRemaining}) via ${sourceTransport.transportType}")

        val seenDao = database.seenPacketDao()
        val msgDao = database.deadNetMessageDao()

        // 1. Deduplication check
        if (seenDao.hasSeen(msgIdStr)) {
            log("Packet $msgIdStr already seen. Dropping.")
            return
        }

        // 2. Record as seen
        seenDao.insert(SeenPacket(msgId = msgIdStr, receivedAt = System.currentTimeMillis()))

        // 3. Check for Reverse ACK
        if (packet.msgType == PacketType.REVERSE_ACK.code) {
            log("Received Reverse ACK for msgId $msgIdStr. Marking delivered.")
            msgDao.markDelivered(msgIdStr)
            return
        }

        // 4. Check for SOS Broadcast
        if (packet.msgType == PacketType.SOS.code || packet.isBroadcast) {
            log("EMERGENCY SOS broadcast received! Triggering siren alert & multi-transport relay.")
            _sosAlertFlow.tryEmit(packet)

            // Save SOS message locally
            val sosText = try {
                String(packet.payload, Charsets.UTF_8)
            } catch (_: Exception) {
                "Emergency SOS payload (${packet.payload.size} bytes)"
            }

            msgDao.insert(
                DeadNetMessage(
                    msgId = msgIdStr,
                    senderPubKey = packet.senderPubKey,
                    recipientPubKey = packet.recipientPubKey,
                    ciphertext = packet.payload,
                    tokensRemaining = 0,
                    hopCount = (packet.hopCount + 1).coerceAtMost(255),
                    createdAt = System.currentTimeMillis(),
                    ttlExpiresAt = packet.ttl * 1000L,
                    isDelivered = true,
                    isBroadcast = true,
                    decryptedText = sosText
                )
            )

            // Re-broadcast to all other active transports (mesh flood)
            val forwardPacket = packet.copy(hopCount = (packet.hopCount + 1).toByte())
            transports.forEach { transport ->
                if (transport != sourceTransport && transport.isRunning) {
                    transport.sendPacket(forwardPacket)
                }
            }
            return
        }

        // 5. Target Recipient Check
        val isForMe = packet.recipientPubKey.contentEquals(ownKeyPair.publicKey)
        if (isForMe) {
            log("Packet $msgIdStr is addressed to THIS NODE! Decrypting payload...")
            var decryptedText: String? = null
            try {
                val plaintext = DeadNetCrypto.decrypt(ownKeyPair.privateKey, packet.payload)
                decryptedText = String(plaintext, Charsets.UTF_8)
                log("Successfully decrypted message: \"$decryptedText\"")
            } catch (e: Exception) {
                log("Failed to decrypt payload for $msgIdStr: ${e.message}")
            }

            msgDao.insert(
                DeadNetMessage(
                    msgId = msgIdStr,
                    senderPubKey = packet.senderPubKey,
                    recipientPubKey = packet.recipientPubKey,
                    ciphertext = packet.payload,
                    tokensRemaining = 0,
                    hopCount = (packet.hopCount + 1).coerceAtMost(255),
                    createdAt = System.currentTimeMillis(),
                    ttlExpiresAt = packet.ttl * 1000L,
                    isDelivered = true,
                    isBroadcast = false,
                    decryptedText = decryptedText,
                    isOutgoing = false
                )
            )

            // Emit Reverse ACK along the link
            log("Generating Reverse ACK for packet $msgIdStr...")
            val ackPacket = DeadNetPacket(
                msgType = PacketType.REVERSE_ACK.code,
                msgId = packet.msgId,
                senderPubKey = ownKeyPair.publicKey,
                recipientPubKey = packet.senderPubKey,
                ttl = (System.currentTimeMillis() / 1000L) + 3600,
                hopCount = 0,
                tokensRemaining = 0,
                payload = ByteArray(0)
            )
            sourceTransport.sendPacket(ackPacket)
            return
        }

        // 6. Store-Carry-Forward (Store payload with tokens)
        val incomingTokens = packet.tokensRemaining.toInt() and 0xFF
        val nextHop = (packet.hopCount + 1).coerceAtMost(255)
        log("Storing carried packet $msgIdStr for store-carry-forward. Tokens: $incomingTokens, NextHop: $nextHop")

        msgDao.insert(
            DeadNetMessage(
                msgId = msgIdStr,
                senderPubKey = packet.senderPubKey,
                recipientPubKey = packet.recipientPubKey,
                ciphertext = packet.payload,
                tokensRemaining = incomingTokens,
                hopCount = nextHop,
                createdAt = System.currentTimeMillis(),
                ttlExpiresAt = packet.ttl * 1000L,
                isDelivered = false,
                isBroadcast = false,
                decryptedText = null,
                isOutgoing = false
            )
        )
    }

    /**
     * Spray-and-Wait Forwarding logic invoked when connecting to a neighbor peer.
     */
    suspend fun onNeighborEncountered(peer: PeerNode, transport: TransportLayer) {
        val msgDao = database.deadNetMessageDao()

        // 1. Check Wait Phase messages (tokens == 1) to see if this peer is the exact recipient
        val waitPhaseMessages = msgDao.getWaitPhaseMessages()
        for (msg in waitPhaseMessages) {
            val peerPubKeyHex = peer.id
            val targetKeyHex = msg.recipientNodeId
            if (peerPubKeyHex.equals(targetKeyHex, ignoreCase = true)) {
                log("Encountered direct target recipient ($targetKeyHex) in Wait Phase! Delivering msg ${msg.msgId}")
                val packet = DeadNetPacket(
                    msgType = PacketType.DIRECT.code,
                    msgId = UUID.fromString(msg.msgId),
                    senderPubKey = msg.senderPubKey,
                    recipientPubKey = msg.recipientPubKey,
                    ttl = msg.ttlExpiresAt / 1000L,
                    hopCount = msg.hopCount.toByte(),
                    tokensRemaining = 1,
                    payload = msg.ciphertext
                )
                transport.sendPacket(packet, peer)
            }
        }

        // 2. Query Spray Phase messages (tokens > 1)
        val forwardable = msgDao.getForwardableMessages()
        for (msg in forwardable) {
            val currentL = msg.tokensRemaining
            if (currentL <= 1) continue

            // Split tokens: give floor(L/2) to peer; keep ceil(L/2) locally
            val peerTokens = currentL / 2
            val localTokens = currentL - peerTokens

            log("Spray-and-Wait: Splitting message ${msg.msgId} (L=$currentL -> local=$localTokens, peer=$peerTokens)")
            msgDao.updateTokens(msg.msgId, localTokens)

            val forwardPacket = DeadNetPacket(
                msgType = PacketType.SPRAY_AND_WAIT.code,
                msgId = UUID.fromString(msg.msgId),
                senderPubKey = msg.senderPubKey,
                recipientPubKey = msg.recipientPubKey,
                ttl = msg.ttlExpiresAt / 1000L,
                hopCount = msg.hopCount.toByte(),
                tokensRemaining = peerTokens.toByte(),
                payload = msg.ciphertext
            )
            transport.sendPacket(forwardPacket, peer)
        }
    }

    /**
     * Creates and stores an outbound direct encrypted message.
     */
    suspend fun sendDirectMessage(recipientPubKey: ByteArray, text: String): DeadNetPacket {
        val msgId = UUID.randomUUID()
        val ciphertext = DeadNetCrypto.encrypt(recipientPubKey, text.toByteArray(Charsets.UTF_8))
        val nowSec = System.currentTimeMillis() / 1000L
        val ttlSec = nowSec + DEFAULT_TTL_SECONDS

        val packet = DeadNetPacket(
            msgType = PacketType.SPRAY_AND_WAIT.code,
            msgId = msgId,
            senderPubKey = ownKeyPair.publicKey,
            recipientPubKey = recipientPubKey,
            ttl = ttlSec,
            hopCount = 0,
            tokensRemaining = DEFAULT_TOKEN_QUOTA.toByte(),
            payload = ciphertext
        )

        // Store locally in database
        database.deadNetMessageDao().insert(
            DeadNetMessage(
                msgId = msgId.toString(),
                senderPubKey = ownKeyPair.publicKey,
                recipientPubKey = recipientPubKey,
                ciphertext = ciphertext,
                tokensRemaining = DEFAULT_TOKEN_QUOTA,
                hopCount = 0,
                createdAt = System.currentTimeMillis(),
                ttlExpiresAt = ttlSec * 1000L,
                isDelivered = false,
                isBroadcast = false,
                decryptedText = text,
                isOutgoing = true
            )
        )

        // Dispatch across all active transports
        log("Created outbound direct message $msgId for ${recipientPubKey.toHexString().take(8)}")
        transports.forEach { it.sendPacket(packet) }
        return packet
    }

    /**
     * Creates and blasts an emergency SOS broadcast across all transports.
     */
    suspend fun blastSos(latitude: Double, longitude: Double, note: String): DeadNetPacket {
        val msgId = UUID.randomUUID()
        val sosPayloadStr = "SOS|LAT:$latitude|LON:$longitude|NOTE:$note|TS:${System.currentTimeMillis()}"
        val payloadBytes = sosPayloadStr.toByteArray(Charsets.UTF_8)
        val nowSec = System.currentTimeMillis() / 1000L
        val ttlSec = nowSec + (3600 * 48) // 48 hours TTL for emergency

        val packet = DeadNetPacket(
            msgType = PacketType.SOS.code,
            msgId = msgId,
            senderPubKey = ownKeyPair.publicKey,
            recipientPubKey = DeadNetPacket.BROADCAST_KEY,
            ttl = ttlSec,
            hopCount = 0,
            tokensRemaining = 0,
            payload = payloadBytes
        )

        database.deadNetMessageDao().insert(
            DeadNetMessage(
                msgId = msgId.toString(),
                senderPubKey = ownKeyPair.publicKey,
                recipientPubKey = DeadNetPacket.BROADCAST_KEY,
                ciphertext = payloadBytes,
                tokensRemaining = 0,
                hopCount = 0,
                createdAt = System.currentTimeMillis(),
                ttlExpiresAt = ttlSec * 1000L,
                isDelivered = true,
                isBroadcast = true,
                decryptedText = sosPayloadStr,
                isOutgoing = true
            )
        )

        log("BLASTING SOS PACKET $msgId (LAT: $latitude, LON: $longitude) across all transports!")
        transports.forEach { it.sendPacket(packet) }
        return packet
    }

    private fun startEvictionDaemon() {
        evictionJob?.cancel()
        evictionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(EVICTION_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val deleted = database.deadNetMessageDao().deleteExpired(now)
                if (deleted > 0) {
                    log("Queue Eviction Daemon: Purged $deleted expired message(s).")
                }
            }
        }
    }
}
