package org.deadnet.routing

import android.os.Build
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
import org.deadnet.crypto.toHexString
import org.deadnet.data.DeadNetDatabase
import org.deadnet.data.DeadNetMessage
import org.deadnet.data.SeenPacket
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.protocol.PacketType
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportLayer
import org.deadnet.transport.TransportType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Delay-Tolerant Mesh Routing Engine implementing:
 * - Peer Handshake & Verified Public Key Registry
 * - Private 1-on-1 and Broadcast E2E Encryption / Decryption
 * - Store-Carry-Forward Spray-and-Wait routing (L=5 quota)
 * - Ingress packet deduplication via SeenPacket table
 * - Reverse ACK processing
 * - Incoming message notifications
 * - Queue Eviction Daemon (10-minute periodic sweep)
 * - File transfer packet routing via FileTransferManager
 */
class DtnRoutingEngine(
    private val database: DeadNetDatabase,
    private val ownKeyPair: DeadNetCrypto.KeyPair,
    private val scope: CoroutineScope,
    val fileTransferManager: FileTransferManager
) {
    companion object {
        const val DEFAULT_TOKEN_QUOTA = 5
        const val DEFAULT_TTL_SECONDS = 86400L // 24 hours
        const val EVICTION_INTERVAL_MS = 600000L // 10 minutes
    }

    private val transports = mutableListOf<TransportLayer>()
    private var evictionJob: Job? = null

    // Known peers discovered through verified Handshake packets (NodeID -> PeerNode)
    private val knownPeersMap = ConcurrentHashMap<String, PeerNode>()
    private val _knownPeersFlow = MutableStateFlow<List<PeerNode>>(emptyList())
    val knownPeersFlow: StateFlow<List<PeerNode>> = _knownPeersFlow.asStateFlow()

    // Notification callback: (senderTitle, messageText, conversationId)
    var onIncomingMessage: ((senderTitle: String, messageText: String, conversationId: String) -> Unit)? = null

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
        transport.onHandshakeReady = {
            broadcastHandshake()
        }
        fileTransferManager.registerTransport(transport)
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
     * Broadcasts a HELLO/HANDSHAKE packet so connected peers learn our 32-byte public key and name.
     */
    fun broadcastHandshake() {
        scope.launch(Dispatchers.IO) {
            val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            val payload = deviceName.toByteArray(Charsets.UTF_8)
            val packet = DeadNetPacket(
                msgType = PacketType.HANDSHAKE.code,
                msgId = UUID.randomUUID(),
                senderPubKey = ownKeyPair.publicKey,
                recipientPubKey = DeadNetPacket.BROADCAST_KEY,
                ttl = (System.currentTimeMillis() / 1000L) + 3600,
                hopCount = 0,
                tokensRemaining = 0,
                payload = payload
            )
            log("Broadcasting Handshake (Node: ${ownKeyPair.nodeId}, Name: $deviceName)")
            transports.forEach { transport ->
                try {
                    transport.sendPacket(packet)
                } catch (e: Exception) {
                    log("Failed to send handshake on ${transport.transportType}: ${e.message}")
                }
            }
        }
    }

    /**
     * Ingress pipeline processing for packets received across any transport interface.
     */
    suspend fun handleIngressPacket(packet: DeadNetPacket, sourceTransport: TransportLayer) {
        val msgIdStr = packet.msgId.toString()
        val seenDao = database.seenPacketDao()
        val msgDao = database.deadNetMessageDao()

        // 0. Delegate file transfer packets to FileTransferManager
        if (packet.msgType == PacketType.FILE_METADATA.code || packet.msgType == PacketType.FILE_CHUNK.code) {
            log("Received file transfer packet (type ${packet.msgType}) — delegating to FileTransferManager")
            fileTransferManager.handleIncomingPacket(packet)
            return
        }

        // 1. Process Handshake packets
        if (packet.msgType == PacketType.HANDSHAKE.code) {
            val senderPub = packet.senderPubKey
            val senderNodeId = DeadNetCrypto.deriveNodeId(senderPub)
            if (senderNodeId != ownKeyPair.nodeId) {
                val peerName = try {
                    String(packet.payload, Charsets.UTF_8).ifBlank { "DeadNet Node $senderNodeId" }
                } catch (_: Exception) {
                    "DeadNet Node $senderNodeId"
                }

                val isNew = !knownPeersMap.containsKey(senderNodeId)
                val peer = PeerNode(
                    id = senderNodeId,
                    name = peerName,
                    transportType = sourceTransport.transportType,
                    rssi = -50,
                    lastSeen = System.currentTimeMillis(),
                    isConnected = true,
                    statusText = "CONNECTED",
                    publicKey = senderPub
                )
                knownPeersMap[senderNodeId] = peer
                _knownPeersFlow.value = knownPeersMap.values.toList()

                log("Received Handshake from $peerName. Public key registered.")

                // Reply with our handshake if we just learned of them
                if (isNew) {
                    broadcastHandshake()
                }

                // Opportunistic DTN Sync: Flush stored broadcast messages and pending chats to this peer
                syncStoredMessagesWithPeer(senderPub, sourceTransport)
            }
            return
        }

        // 2. Ingress deduplication check
        if (seenDao.hasSeen(msgIdStr)) {
            log("Packet $msgIdStr already processed. Dropping duplicate.")
            return
        }
        seenDao.insert(SeenPacket(msgId = msgIdStr, receivedAt = System.currentTimeMillis()))

        // 3. Process Reverse ACK
        if (packet.msgType == PacketType.REVERSE_ACK.code) {
            log("Received Reverse ACK for msgId $msgIdStr. Marking delivered.")
            msgDao.markDelivered(msgIdStr)
            return
        }

        // 4. Target Recipient Check: Is it addressed to THIS NODE?
        val isForMe = packet.recipientPubKey.contentEquals(ownKeyPair.publicKey)
        if (isForMe) {
            val senderPub = packet.senderPubKey
            val senderNodeId = DeadNetCrypto.deriveNodeId(senderPub)
            log("Packet $msgIdStr is addressed to THIS NODE. Decrypting...")

            var decryptedText: String? = null
            try {
                val plaintext = DeadNetCrypto.decrypt(ownKeyPair.privateKey, packet.payload)
                decryptedText = String(plaintext, Charsets.UTF_8)
                log("Successfully decrypted private message: \"$decryptedText\"")
            } catch (e: Exception) {
                log("Decryption failed for $msgIdStr: ${e.message}")
            }

            val senderName = knownPeersMap[senderNodeId]?.name ?: "Nearby Peer"

            msgDao.insert(
                DeadNetMessage(
                    msgId = msgIdStr,
                    senderPubKey = senderPub,
                    recipientPubKey = packet.recipientPubKey,
                    ciphertext = packet.payload,
                    tokensRemaining = 0,
                    hopCount = (packet.hopCount + 1).coerceAtMost(255),
                    createdAt = System.currentTimeMillis(),
                    ttlExpiresAt = packet.ttl * 1000L,
                    isDelivered = true,
                    isBroadcast = false,
                    decryptedText = decryptedText,
                    isOutgoing = false,
                    conversationId = senderNodeId,
                    conversationTitle = senderName
                )
            )

            // Notify user of incoming message
            if (decryptedText != null) {
                onIncomingMessage?.invoke(senderName, decryptedText, senderNodeId)
            }

            // Emit Reverse ACK along the link
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

        // 5. Broadcast Message Check
        if (packet.isBroadcast) {
            log("Received Mesh Broadcast packet $msgIdStr. Decrypting broadcast payload...")
            var decryptedText: String? = null
            try {
                val plaintext = DeadNetCrypto.decrypt(ownKeyPair.privateKey, packet.payload)
                decryptedText = String(plaintext, Charsets.UTF_8)
                log("Decrypted broadcast: \"$decryptedText\"")
            } catch (e: Exception) {
                decryptedText = try { String(packet.payload, Charsets.UTF_8) } catch (_: Exception) { null }
            }

            val senderNodeId = DeadNetCrypto.deriveNodeId(packet.senderPubKey)
            val senderName = knownPeersMap[senderNodeId]?.name ?: "Node $senderNodeId"

            msgDao.insert(
                DeadNetMessage(
                    msgId = msgIdStr,
                    senderPubKey = packet.senderPubKey,
                    recipientPubKey = DeadNetPacket.BROADCAST_KEY,
                    ciphertext = packet.payload,
                    tokensRemaining = 0,
                    hopCount = (packet.hopCount + 1).coerceAtMost(255),
                    createdAt = System.currentTimeMillis(),
                    ttlExpiresAt = packet.ttl * 1000L,
                    isDelivered = true,
                    isBroadcast = true,
                    decryptedText = decryptedText,
                    isOutgoing = false,
                    conversationId = "BROADCAST",
                    conversationTitle = "Mesh Broadcast"
                )
            )

            if (decryptedText != null) {
                onIncomingMessage?.invoke("Mesh Broadcast ($senderName)", decryptedText, "BROADCAST")
            }

            // Re-flood broadcast to all other interfaces
            val forwardPacket = packet.copy(hopCount = (packet.hopCount + 1).toByte())
            transports.forEach { transport ->
                if (transport != sourceTransport && transport.isRunning) {
                    transport.sendPacket(forwardPacket)
                }
            }
            return
        }

        // 6. Store-Carry-Forward (Store payload with tokens for multi-hop delay-tolerant forwarding)
        val incomingTokens = packet.tokensRemaining.toInt() and 0xFF
        val nextHop = (packet.hopCount + 1).coerceAtMost(255)
        val targetNodeId = DeadNetCrypto.deriveNodeId(packet.recipientPubKey)
        log("Storing carried packet $msgIdStr for target $targetNodeId (Tokens: $incomingTokens, Hops: $nextHop)")

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
                isOutgoing = false,
                conversationId = targetNodeId,
                conversationTitle = "Carried for $targetNodeId"
            )
        )
    }

    /**
     * Creates and stores an outbound message (Private direct or Mesh Broadcast).
     */
    suspend fun sendDirectMessage(
        recipientPubKey: ByteArray,
        text: String,
        recipientName: String = ""
    ): DeadNetPacket? {
        return try {
            val isBroadcast = DeadNetCrypto.isBroadcastKey(recipientPubKey)
            val effectiveRecipient = if (isBroadcast) DeadNetPacket.BROADCAST_KEY else recipientPubKey
            val msgId = UUID.randomUUID()
            val ciphertext = DeadNetCrypto.encrypt(effectiveRecipient, text.toByteArray(Charsets.UTF_8))
            val nowSec = System.currentTimeMillis() / 1000L
            val ttlSec = nowSec + DEFAULT_TTL_SECONDS

            val targetNodeId = if (isBroadcast) "BROADCAST" else DeadNetCrypto.deriveNodeId(effectiveRecipient)
            val title = if (isBroadcast) "Mesh Broadcast" else if (recipientName.isNotBlank()) recipientName else "Node $targetNodeId"

            val packet = DeadNetPacket(
                msgType = if (isBroadcast) PacketType.DIRECT.code else PacketType.SPRAY_AND_WAIT.code,
                msgId = msgId,
                senderPubKey = ownKeyPair.publicKey,
                recipientPubKey = effectiveRecipient,
                ttl = ttlSec,
                hopCount = 0,
                tokensRemaining = if (isBroadcast) 0.toByte() else DEFAULT_TOKEN_QUOTA.toByte(),
                payload = ciphertext
            )

            // Store locally in database
            database.deadNetMessageDao().insert(
                DeadNetMessage(
                    msgId = msgId.toString(),
                    senderPubKey = ownKeyPair.publicKey,
                    recipientPubKey = effectiveRecipient,
                    ciphertext = ciphertext,
                    tokensRemaining = if (isBroadcast) 0 else DEFAULT_TOKEN_QUOTA,
                    hopCount = 0,
                    createdAt = System.currentTimeMillis(),
                    ttlExpiresAt = ttlSec * 1000L,
                    isDelivered = isBroadcast,
                    isBroadcast = isBroadcast,
                    decryptedText = text,
                    isOutgoing = true,
                    conversationId = targetNodeId,
                    conversationTitle = title
                )
            )

            log("Dispatched message $msgId to $targetNodeId ($title)")
            transports.forEach { it.sendPacket(packet) }
            packet
        } catch (e: Exception) {
            log("Error sending message: ${e.message}")
            null
        }
    }

    suspend fun deleteMessage(msgId: String) {
        database.deadNetMessageDao().deleteMessage(msgId)
        log("Deleted message $msgId")
    }

    suspend fun deleteConversation(conversationId: String) {
        database.deadNetMessageDao().deleteConversation(conversationId)
        log("Cleared conversation $conversationId")
    }

    suspend fun clearAllMessages() {
        database.deadNetMessageDao().deleteAllMessages()
        log("Cleared all stored messages")
    }

    private fun startEvictionDaemon() {
        evictionJob?.cancel()
        evictionJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(EVICTION_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val deleted = database.deadNetMessageDao().deleteExpired(now)
                if (deleted > 0) {
                    log("Eviction Daemon: Purged $deleted expired messages.")
                }
            }
        }
    }

    private fun syncStoredMessagesWithPeer(peerPubKey: ByteArray, transport: TransportLayer) {
        scope.launch(Dispatchers.IO) {
            val msgDao = database.deadNetMessageDao()
            val now = System.currentTimeMillis()

            // 1. Sync recent broadcast messages from the last 2 hours
            val recentBroadcasts = msgDao.getRecentBroadcastMessages(now - 2 * 3600_000L)
            if (recentBroadcasts.isNotEmpty()) {
                log("Flushing ${recentBroadcasts.size} stored broadcast message(s) to connected peer...")
                recentBroadcasts.forEach { msg ->
                    val packet = DeadNetPacket(
                        msgType = PacketType.DIRECT.code,
                        msgId = java.util.UUID.fromString(msg.msgId),
                        senderPubKey = msg.senderPubKey,
                        recipientPubKey = DeadNetPacket.BROADCAST_KEY,
                        ttl = (msg.ttlExpiresAt / 1000L),
                        hopCount = msg.hopCount.toByte(),
                        tokensRemaining = 0,
                        payload = msg.ciphertext
                    )
                    try {
                        transport.sendPacket(packet)
                    } catch (_: Exception) {}
                }
            }

            // 2. Deliver pending private direct messages for this peer
            val pendingDirects = msgDao.getPendingDirectMessagesForRecipient(peerPubKey)
            if (pendingDirects.isNotEmpty()) {
                log("Delivering ${pendingDirects.size} pending direct message(s) to connected recipient...")
                pendingDirects.forEach { msg ->
                    val packet = DeadNetPacket(
                        msgType = PacketType.DIRECT.code,
                        msgId = java.util.UUID.fromString(msg.msgId),
                        senderPubKey = msg.senderPubKey,
                        recipientPubKey = peerPubKey,
                        ttl = (msg.ttlExpiresAt / 1000L),
                        hopCount = msg.hopCount.toByte(),
                        tokensRemaining = 0,
                        payload = msg.ciphertext
                    )
                    try {
                        transport.sendPacket(packet)
                    } catch (_: Exception) {}
                }
            }

            // 3. Spray-and-Wait Forwarding: forward carried DTN messages with tokensRemaining > 1
            val forwardable = msgDao.getForwardableMessages()
            forwardable.forEach { msg ->
                if (!msg.senderPubKey.contentEquals(peerPubKey) && !msg.recipientPubKey.contentEquals(peerPubKey)) {
                    val currentTokens = msg.tokensRemaining
                    val peerTokens = currentTokens / 2
                    val localTokens = currentTokens - peerTokens
                    msgDao.updateTokens(msg.msgId, localTokens)

                    val forwardPacket = DeadNetPacket(
                        msgType = PacketType.SPRAY_AND_WAIT.code,
                        msgId = java.util.UUID.fromString(msg.msgId),
                        senderPubKey = msg.senderPubKey,
                        recipientPubKey = msg.recipientPubKey,
                        ttl = (msg.ttlExpiresAt / 1000L),
                        hopCount = (msg.hopCount + 1).toByte(),
                        tokensRemaining = peerTokens.toByte(),
                        payload = msg.ciphertext
                    )
                    log("Relaying DTN packet ${msg.msgId} (split: $peerTokens to peer, $localTokens local)")
                    try {
                        transport.sendPacket(forwardPacket)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
