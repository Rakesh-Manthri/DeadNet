package org.deadnet.transport.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
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
    private var connectionTimeoutJob: Job? = null
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
            log("Error: Wi-Fi P2P Service not supported on this hardware.")
            _isRunning = false
            return
        }

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
            log("Wi-Fi P2P Channel disconnected! Re-initializing...")
            channel = null
            start()
        }

        socketEngine = WifiDirectSocketEngine(
            scope = scope,
            onPacketReceived = { packet ->
                onPacketReceived?.invoke(packet)
            },
            onLog = { msg -> log("[Socket] $msg") },
            onConnectionClosed = {
                log("Socket connection closed.")
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
        connectionTimeoutJob?.cancel()

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

    fun triggerManualScan() {
        log("Manual scan requested by user.")
        triggerDiscovery()
    }

    private fun startDiscoveryLoop() {
        discoveryLoopJob?.cancel()
        discoveryLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isRunning) {
                if (!isConnectingOrConnected.get()) {
                    triggerDiscovery()
                }
                delay(15000) // Re-scan every 15 seconds
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerDiscovery() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.discoverPeers(ch, object : ActionListener {
                override fun onSuccess() {
                    log("Wi-Fi P2P discoverPeers() initiated.")
                }

                override fun onFailure(reason: Int) {
                    val reasonDesc = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
                        WifiP2pManager.ERROR -> "Internal Error"
                        WifiP2pManager.BUSY -> "Framework Busy"
                        else -> "Code $reason"
                    }
                    log("discoverPeers() failed: $reasonDesc")
                }
            })
        } catch (e: SecurityException) {
            log("Permission required for peer discovery: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        if (!isConnectingOrConnected.compareAndSet(false, true)) {
            log("Resetting previous connection state to connect to $deviceAddress...")
            isConnectingOrConnected.set(true)
        }

        log("Initiating P2P connection to $deviceAddress with PBC setup...")
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 7 // Balanced intent
        }

        // 30-second connection timeout guard
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(30000)
            if (isConnectingOrConnected.get() && currentGroupInfo == null) {
                log("Connection attempt timed out. Resetting connection lock.")
                isConnectingOrConnected.set(false)
            }
        }

        try {
            mgr.connect(ch, config, object : ActionListener {
                override fun onSuccess() {
                    log("P2P Connect request accepted by framework for $deviceAddress")
                }

                override fun onFailure(reason: Int) {
                    log("P2P Connect failed for $deviceAddress (code $reason)")
                    isConnectingOrConnected.set(false)
                    connectionTimeoutJob?.cancel()
                }
            })
        } catch (e: SecurityException) {
            log("Missing permission to connect: ${e.message}")
            isConnectingOrConnected.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAndResumeDiscovery() {
        log("Disconnecting P2P group and resuming discovery...")
        socketEngine?.stop()
        disconnectGroup()
        currentGroupInfo = null
        isConnectingOrConnected.set(false)
        connectionTimeoutJob?.cancel()

        scope.launch {
            delay(2000)
            if (_isRunning) {
                triggerDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.removeGroup(ch, object : ActionListener {
                override fun onSuccess() {
                    log("P2P Group cleared.")
                }

                override fun onFailure(reason: Int) {
                    // Ignore failure if no group existed
                }
            })
        } catch (_: Exception) {}
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
                        log("Wi-Fi P2P Radio: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                        if (isEnabled && _isRunning) {
                            triggerDiscovery()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val mgr = wifiP2pManager ?: return
                        val ch = channel ?: return
                        try {
                            mgr.requestPeers(ch) { peers: WifiP2pDeviceList? ->
                                handlePeersAvailable(peers)
                            }
                        } catch (e: SecurityException) {
                            log("requestPeers permission error: ${e.message}")
                        }
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

                        val connected = networkInfo?.isConnected == true
                        log("Wi-Fi P2P connection changed. IsConnected: $connected")
                        if (connected) {
                            try {
                                mgr.requestConnectionInfo(ch) { info ->
                                    handleConnectionInfoAvailable(info)
                                }
                            } catch (e: SecurityException) {
                                log("requestConnectionInfo permission error: ${e.message}")
                            }
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
                        log("This device: ${device?.deviceName} (${device?.deviceAddress})")
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
            val statusDesc = when (device.status) {
                WifiP2pDevice.CONNECTED -> "CONNECTED"
                WifiP2pDevice.INVITED -> "INVITED"
                WifiP2pDevice.FAILED -> "FAILED"
                WifiP2pDevice.AVAILABLE -> "AVAILABLE"
                WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
                else -> "UNKNOWN"
            }
            val isDevConnected = device.status == WifiP2pDevice.CONNECTED

            PeerNode(
                id = device.deviceAddress.replace(":", "").takeLast(8).uppercase(),
                name = device.deviceName.ifBlank { "DeadNet Peer" },
                transportType = TransportType.WIFI_DIRECT,
                rssi = -50,
                lastSeen = System.currentTimeMillis(),
                address = device.deviceAddress,
                isGroupOwner = false,
                isConnected = isDevConnected,
                statusText = statusDesc
            )
        }
        _peersFlow.value = mappedPeers
    }

    private fun handleConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        currentGroupInfo = info
        isConnectingOrConnected.set(true)
        connectionTimeoutJob?.cancel()

        log("P2P Group Formed! IsGO: ${info.isGroupOwner}, GO Host: ${info.groupOwnerAddress?.hostAddress}")

        if (info.isGroupOwner) {
            log("This node is Group Owner (GO). Launching ServerSocket(8888)...")
            socketEngine?.startServer()
        } else {
            val goHost = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            log("This node is Group Client. Connecting to GO at $goHost:8888...")
            socketEngine?.startClient(goHost)
        }
    }

    private fun log(message: String) {
        onLogEvent?.invoke("[WifiDirect] $message")
    }
}
