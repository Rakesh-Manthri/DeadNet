package org.deadnet.transport.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportLayer
import org.deadnet.transport.TransportType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Direct (P2P Transport) Implementation.
 * Manages peer discovery loop, group formation (GO vs Client),
 * and delegates socket streaming to [WifiDirectSocketEngine].
 */
class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : TransportLayer {

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private var _isRunning = false
    override val isRunning: Boolean
        get() = _isRunning

    private val _peersFlow = MutableStateFlow<List<PeerNode>>(emptyList())
    override val peersFlow: StateFlow<List<PeerNode>> = _peersFlow.asStateFlow()

    override var onPacketReceived: ((DeadNetPacket) -> Unit)? = null
    override var onLogEvent: ((String) -> Unit)? = null

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: Channel? = null
    private var receiver: BroadcastReceiver? = null

    private var discoveryLoopJob: Job? = null
    private val isConnectingOrConnected = AtomicBoolean(false)
    private var currentGroupInfo: WifiP2pInfo? = null

    private var socketEngine: WifiDirectSocketEngine? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        if (_isRunning) return
        _isRunning = true
        log("Initializing Wi-Fi Direct Transport...")

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            log("Error: Wi-Fi P2P Service not available on this device.")
            _isRunning = false
            return
        }

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        socketEngine = WifiDirectSocketEngine(
            scope = scope,
            onPacketReceived = { packet ->
                onPacketReceived?.invoke(packet)
            },
            onLog = { msg -> log("[Socket] $msg") },
            onExchangeCompleted = {
                log("Exchange completed. Disconnecting group to resume discovery cycle...")
                disconnectAndResumeDiscovery()
            }
        )

        registerReceiver()
        startDiscoveryLoop()
    }

    override fun stop() {
        if (!_isRunning) return
        _isRunning = false
        log("Stopping Wi-Fi Direct Transport...")

        discoveryLoopJob?.cancel()
        discoveryLoopJob = null

        unregisterReceiver()
        socketEngine?.stop()
        disconnectGroup()

        _peersFlow.value = emptyList()
        currentGroupInfo = null
        isConnectingOrConnected.set(false)
    }

    override suspend fun sendPacket(packet: DeadNetPacket, targetPeer: PeerNode?): Boolean {
        log("Queueing packet ${packet.msgId} (type ${packet.packetType}) for Wi-Fi Direct dispatch")
        socketEngine?.queueOutboundPacket(packet)

        // If not connected and target peer specified, trigger connection
        if (!isConnectingOrConnected.get() && targetPeer != null && targetPeer.address.isNotBlank()) {
            connectToDevice(targetPeer.address)
        }
        return true
    }

    private fun startDiscoveryLoop() {
        discoveryLoopJob?.cancel()
        discoveryLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isRunning) {
                if (!isConnectingOrConnected.get()) {
                    triggerDiscovery()
                }
                delay(20000) // Repeat every 20 seconds
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerDiscovery() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        log("Triggering Wi-Fi P2P discoverPeers()...")
        mgr.discoverPeers(ch, object : ActionListener {
            override fun onSuccess() {
                log("Wi-Fi P2P discovery initiated successfully.")
            }

            override fun onFailure(reason: Int) {
                log("Wi-Fi P2P discovery failed. Reason code: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        if (!isConnectingOrConnected.compareAndSet(false, true)) {
            log("Already connecting or connected. Skipping connect request for $deviceAddress")
            return
        }

        log("Initiating P2P connection to $deviceAddress...")
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            // Group owner intent: balanced 7
            groupOwnerIntent = 7
        }

        mgr.connect(ch, config, object : ActionListener {
            override fun onSuccess() {
                log("P2P Connect request initiated successfully to $deviceAddress")
            }

            override fun onFailure(reason: Int) {
                log("P2P Connect failed to $deviceAddress (code $reason)")
                isConnectingOrConnected.set(false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.removeGroup(ch, object : ActionListener {
            override fun onSuccess() {
                log("P2P Group removed successfully.")
            }

            override fun onFailure(reason: Int) {
                log("Failed to remove P2P group (code $reason)")
            }
        })
    }

    private fun disconnectAndResumeDiscovery() {
        socketEngine?.stop()
        disconnectGroup()
        currentGroupInfo = null
        isConnectingOrConnected.set(false)

        scope.launch {
            delay(3000)
            if (_isRunning && !isConnectingOrConnected.get()) {
                triggerDiscovery()
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        log("Wi-Fi P2P state changed: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        log("Wi-Fi P2P peers changed. Requesting peer list...")
                        val mgr = wifiP2pManager ?: return
                        val ch = channel ?: return
                        mgr.requestPeers(ch, PeerListListener { peers: WifiP2pDeviceList? ->
                            handlePeersAvailable(peers)
                        })
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val mgr = wifiP2pManager ?: return
                        val ch = channel ?: return

                        @Suppress("DEPRECATION")
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        log("Wi-Fi P2P connection changed. IsConnected: ${networkInfo?.isConnected}")
                        if (networkInfo?.isConnected == true) {
                            mgr.requestConnectionInfo(ch, ConnectionInfoListener { info ->
                                handleConnectionInfoAvailable(info)
                            })
                        } else {
                            isConnectingOrConnected.set(false)
                            currentGroupInfo = null
                            socketEngine?.stop()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        log("This device changed: ${device?.deviceName} (${device?.deviceAddress})")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            receiver = null
        }
    }

    private fun handlePeersAvailable(peerList: WifiP2pDeviceList?) {
        val devices = peerList?.deviceList?.toList() ?: emptyList()
        log("Discovered ${devices.size} Wi-Fi Direct peer(s)")

        val mappedPeers = devices.map { device ->
            PeerNode(
                id = device.deviceAddress.replace(":", "").takeLast(8).uppercase(),
                name = device.deviceName.ifBlank { "DeadNet Peer" },
                transportType = TransportType.WIFI_DIRECT,
                rssi = -50, // Android Wi-Fi P2P does not expose raw RSSI on standard API; nominal value
                lastSeen = System.currentTimeMillis(),
                address = device.deviceAddress,
                isGroupOwner = false,
                isConnected = isConnectingOrConnected.get()
            )
        }
        _peersFlow.value = mappedPeers

        // Opportunistic mesh connection: if not connected and peers available, pick the first
        if (!isConnectingOrConnected.get() && mappedPeers.isNotEmpty()) {
            val candidate = mappedPeers.first()
            log("Opportunistic auto-connect to peer: ${candidate.name} (${candidate.address})")
            connectToDevice(candidate.address)
        }
    }

    private fun handleConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        currentGroupInfo = info
        isConnectingOrConnected.set(true)

        log("Group formed! GroupOwner: ${info.isGroupOwner}, GO Address: ${info.groupOwnerAddress?.hostAddress}")

        if (info.isGroupOwner) {
            log("Configuring this node as Group Owner (GO). Launching ServerSocket...")
            socketEngine?.startServer()
        } else {
            val goHost = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            log("Configuring this node as Client. Connecting to GO at $goHost...")
            socketEngine?.startClient(goHost)
        }
    }

    private fun log(message: String) {
        onLogEvent?.invoke("[WifiDirect] $message")
    }
}
