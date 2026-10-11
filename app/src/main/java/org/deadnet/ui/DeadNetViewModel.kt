package org.deadnet.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.deadnet.crypto.DeadNetCrypto
import org.deadnet.crypto.toHexString
import org.deadnet.data.DeadNetDatabase
import org.deadnet.data.DeadNetMessage
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.service.DeadNetMeshService
import org.deadnet.transport.PeerNode
import java.util.UUID

class DeadNetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DeadNetDatabase.getDatabase(application)
    private var meshService: DeadNetMeshService? = null

    val messages: StateFlow<List<DeadNetMessage>> = db.deadNetMessageDao()
        .getAllMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _peers = MutableStateFlow<List<PeerNode>>(emptyList())
    val peers: StateFlow<List<PeerNode>> = _peers.asStateFlow()

    private val _knownPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val knownPeers: StateFlow<List<PeerNode>> = _knownPeers.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _ownNodeId = MutableStateFlow("UNKNOWN")
    val ownNodeId: StateFlow<String> = _ownNodeId.asStateFlow()

    private val _ownPubKey = MutableStateFlow("")
    val ownPubKey: StateFlow<String> = _ownPubKey.asStateFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    // Navigation inside Chat tab: null = Conversations List, non-null = Conversation Thread
    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    val myDeviceName: String = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"

    private val _isAutoConnect = MutableStateFlow(true)
    val isAutoConnect: StateFlow<Boolean> = _isAutoConnect.asStateFlow()

    fun toggleAutoConnect(enabled: Boolean) {
        _isAutoConnect.value = enabled
        meshService?.wifiTransport?.isAutoConnectEnabled = enabled
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? DeadNetMeshService.LocalBinder
            meshService = binder?.getService()
            _isServiceBound.value = true

            meshService?.let { s ->
                _ownNodeId.value = s.keyPair.nodeId
                _ownPubKey.value = s.keyPair.publicKey.toHexString()
                s.wifiTransport.isAutoConnectEnabled = _isAutoConnect.value

                // Bind discovered physical Wi-Fi peers flow
                viewModelScope.launch {
                    s.wifiTransport.peersFlow.collect { peerList ->
                        _peers.value = peerList
                    }
                }

                // Bind verified handshake known peers flow
                viewModelScope.launch {
                    s.routingEngine.knownPeersFlow.collect { peerList ->
                        _knownPeers.value = peerList
                        // Automatically merge/migrate any legacy conversations in DB
                        peerList.forEach { kp ->
                            if (kp.address.isNotBlank()) {
                                db.deadNetMessageDao().migrateConversation(kp.address, kp.nodeId, kp.name)
                            }
                        }
                    }
                }

                // Bind logs flow
                viewModelScope.launch {
                    s.routingEngine.logFlow.collect { logList ->
                        _logs.value = logList
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            _isServiceBound.value = false
        }
    }

    init {
        bindMeshService()
    }

    fun bindMeshService() {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, DeadNetMeshService::class.java)
        app.startService(serviceIntent)
        app.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun selectConversation(conversationId: String?) {
        _selectedConversationId.value = conversationId
    }

    fun selectConversationForPeer(peer: PeerNode) {
        val matched = _knownPeers.value.find { 
            it.nodeId == peer.nodeId || 
            (peer.address.isNotBlank() && it.address == peer.address) || 
            it.name.equals(peer.name, ignoreCase = true) 
        } ?: _knownPeers.value.firstOrNull { it.isConnected }
          ?: peer

        val targetConvId = matched.nodeId

        // Migrate any previous messages that might have been stored under peer.address or peer.id
        viewModelScope.launch(Dispatchers.IO) {
            if (peer.address.isNotBlank()) {
                db.deadNetMessageDao().migrateConversation(peer.address, targetConvId, peer.name)
            }
            if (peer.id != targetConvId) {
                db.deadNetMessageDao().migrateConversation(peer.id, targetConvId, peer.name)
            }
        }

        _selectedConversationId.value = targetConvId
    }

    fun sendMessageToConversation(conversationId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val s = meshService ?: return@launch
            try {
                if (conversationId == "BROADCAST") {
                    s.routingEngine.sendDirectMessage(DeadNetPacket.BROADCAST_KEY, text, "Mesh Broadcast")
                } else {
                    // Match peer across known verified peers or active connected peers
                    val peer = _knownPeers.value.find { it.nodeId == conversationId }
                        ?: _knownPeers.value.firstOrNull { it.isConnected }
                        ?: _peers.value.find { it.nodeId == conversationId || it.address == conversationId }
                        ?: _peers.value.firstOrNull { it.isConnected }

                    val recipientKey = if (peer != null && peer.hasPublicKey) {
                        peer.publicKey
                    } else {
                        val connected = _knownPeers.value.firstOrNull { it.hasPublicKey }
                        if (connected != null) {
                            connected.publicKey
                        } else {
                            val parsed = DeadNetCrypto.hexToBytes(conversationId)
                            if (parsed.size == 32) parsed else {
                                val padded = ByteArray(32)
                                System.arraycopy(parsed, 0, padded, 0, parsed.size.coerceAtMost(32))
                                padded
                            }
                        }
                    }

                    val recipientName = peer?.name ?: "Nearby Peer"
                    s.routingEngine.sendDirectMessage(recipientKey, text, recipientName)

                    // Migrate conversation to peer.nodeId if needed
                    if (peer != null && conversationId != peer.nodeId) {
                        db.deadNetMessageDao().migrateConversation(conversationId, peer.nodeId, peer.name)
                        _selectedConversationId.value = peer.nodeId
                    }
                }
            } catch (e: Exception) {
                s.routingEngine.log("Error sending to $conversationId: ${e.message}")
            }
        }
    }

    fun sendDirectMessage(recipientPubKeyHex: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = meshService ?: return@launch
                val trimmed = recipientPubKeyHex.trim()
                if (trimmed.isEmpty() || trimmed.equals("BROADCAST", ignoreCase = true)) {
                    s.routingEngine.sendDirectMessage(DeadNetPacket.BROADCAST_KEY, text, "Mesh Broadcast")
                    return@launch
                }

                // Check if known peer matches
                val matchedPeer = _knownPeers.value.find { it.nodeId.equals(trimmed, ignoreCase = true) }
                    ?: _peers.value.find { it.nodeId.equals(trimmed, ignoreCase = true) }

                val recipientKey = if (matchedPeer != null && matchedPeer.hasPublicKey) {
                    matchedPeer.publicKey
                } else {
                    val parsed = DeadNetCrypto.hexToBytes(trimmed)
                    if (parsed.size == 32) parsed else {
                        val padded = ByteArray(32)
                        System.arraycopy(parsed, 0, padded, 0, parsed.size.coerceAtMost(32))
                        padded
                    }
                }
                val name = matchedPeer?.name ?: "Node $trimmed"
                s.routingEngine.sendDirectMessage(recipientKey, text, name)
            } catch (_: Exception) {}
        }
    }

    fun deleteMessage(msgId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            meshService?.routingEngine?.deleteMessage(msgId)
                ?: db.deadNetMessageDao().deleteMessage(msgId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            meshService?.routingEngine?.deleteConversation(conversationId)
                ?: db.deadNetMessageDao().deleteConversation(conversationId)
        }
    }

    fun clearAllMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            meshService?.routingEngine?.clearAllMessages()
                ?: db.deadNetMessageDao().deleteAllMessages()
        }
    }

    // Interactive Mesh & DTN Simulation actions
    fun simulateInjectPacket(targetNodeId: String, text: String, initialTokens: Int = 5) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()
            val dummySender = DeadNetCrypto.generateKeyPair()
            val dummyTarget = DeadNetCrypto.generateKeyPair()
            val ciphertext = DeadNetCrypto.encrypt(dummyTarget.publicKey, text.toByteArray(Charsets.UTF_8))
            val now = System.currentTimeMillis()

            db.deadNetMessageDao().insert(
                DeadNetMessage(
                    msgId = msgId,
                    senderPubKey = dummySender.publicKey,
                    recipientPubKey = dummyTarget.publicKey,
                    ciphertext = ciphertext,
                    tokensRemaining = initialTokens,
                    hopCount = 1,
                    createdAt = now,
                    ttlExpiresAt = now + 3600_000L,
                    isDelivered = false,
                    isBroadcast = false,
                    decryptedText = text,
                    isOutgoing = true,
                    conversationId = targetNodeId,
                    conversationTitle = "Simulated: $targetNodeId"
                )
            )
            meshService?.routingEngine?.log("[SIMULATOR] Injected DTN packet $msgId for $targetNodeId (Tokens=$initialTokens)")
        }
    }

    fun simulateSplitTokens(msg: DeadNetMessage, relayNodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTokens = msg.tokensRemaining
            if (currentTokens <= 1) {
                meshService?.routingEngine?.log("[SIMULATOR] Message ${msg.msgId} has L=1 (Wait Phase). Only direct delivery allowed.")
                return@launch
            }
            val peerTokens = currentTokens / 2
            val remainingLocal = currentTokens - peerTokens
            db.deadNetMessageDao().updateTokens(msg.msgId, remainingLocal)
            meshService?.routingEngine?.log("[SIMULATOR] Encountered Relay $relayNodeId! Split tokens: gave $peerTokens to relay, kept $remainingLocal locally.")
        }
    }

    fun simulateDeliverMessage(msgId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deadNetMessageDao().markDelivered(msgId)
            meshService?.routingEngine?.log("[SIMULATOR] Target node encountered! Delivered packet $msgId. Reverse ACK received!")
        }
    }

    fun connectToPeer(peer: PeerNode) {
        meshService?.wifiTransport?.connectToDevice(peer.address)
    }

    fun disconnectPeer() {
        meshService?.wifiTransport?.disconnectAndResumeDiscovery()
    }

    fun triggerScan() {
        meshService?.wifiTransport?.triggerManualScan()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {}
    }
}
