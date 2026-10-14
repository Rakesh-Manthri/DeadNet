package org.deadnet.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deadnet.crypto.DeadNetCrypto
import org.deadnet.crypto.toHexString
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportLayer
import org.deadnet.transport.TransportType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 2: Bluetooth Low Energy (BLE) Ambient Relay Transport.
 *
 * Implements:
 * 1. BLE GATT Server (Peripheral Mode):
 *    - Advertises DeadNet Service UUID + 4-byte truncated Node ID in manufacturer/service data.
 *    - Hosts characteristic for incoming packet fragments.
 * 2. BLE Scanner & GATT Client (Central Mode):
 *    - Continuously scans for nearby DeadNet devices.
 *    - Connects to discovered peers and streams outbound packets over GATT characteristic writes.
 * 3. Transparent Packet Chunking/Reassembly:
 *    - Automatically handles BLE MTU slicing and reassembly.
 */
class BleTransport(
    private val context: Context,
    private val ownKeyPair: DeadNetCrypto.KeyPair,
    private val scope: CoroutineScope
) : TransportLayer {

    override val transportType: TransportType = TransportType.BLE

    private var _isRunning = false
    override val isRunning: Boolean get() = _isRunning

    private val _peersFlow = MutableStateFlow<List<PeerNode>>(emptyList())
    override val peersFlow: StateFlow<List<PeerNode>> = _peersFlow.asStateFlow()

    override var onPacketReceived: ((DeadNetPacket) -> Unit)? = null
    override var onLogEvent: ((String) -> Unit)? = null
    override var onHandshakeReady: (() -> Unit)? = null

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var leScanner: BluetoothLeScanner? = null

    private var gattServer: BluetoothGattServer? = null
    private var packetCharacteristic: BluetoothGattCharacteristic? = null

    // Track active client GATT connections keyed by device address
    private val connectedGattClients = ConcurrentHashMap<String, BluetoothGatt>()

    // Serializes chunked writes per device so concurrent sendPacket calls to the
    // same peer cannot interleave chunks and corrupt a packet.
    private val perDeviceWriteMutex = ConcurrentHashMap<String, Mutex>()

    // Throttle auto-connect attempts: device address -> last attempt timestamp.
    private val lastConnectionAttempt = ConcurrentHashMap<String, Long>()
    private val CONNECTION_ATTEMPT_INTERVAL_MS = 15_000L

    // Track subscribed centrals (CCCD-enabled) for server notification dispatch
    private val subscribedCentrals = mutableSetOf<String>()

    // Reassembly buffers for incoming chunked packets keyed by remote device address
    private val incomingBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // Cache of discovered BLE peers
    private val discoveredPeersMap = ConcurrentHashMap<String, PeerNode>()

    private var scanJob: Job? = null

    // BLE GATT connects MUST be issued on the main thread (binder callbacks run
    // on other threads), otherwise connectGatt can silently fail or never connect.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Bounded retry when Bluetooth is off at start(), so BLE doesn't spin forever.
    private var adapterWaitAttempt = 0
    private val MAX_ADAPTER_WAIT = 5

    @SuppressLint("MissingPermission")
    override fun start() {
        if (_isRunning) return
        _isRunning = true
        log("Starting BLE Ambient Transport...")

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            log("Bluetooth adapter disabled or unavailable. BLE transport in standby.")
            // Retry shortly — the user may toggle Bluetooth on right after the
            // service starts, and BLE would otherwise stay dead forever. Guard
            // so we don't spin an unbounded retry loop when the adapter stays off.
            adapterWaitAttempt++
            if (adapterWaitAttempt <= MAX_ADAPTER_WAIT) {
                scope.launch {
                    delay(3000)
                    if (_isRunning) {
                        _isRunning = false // allow start() to re-run setup
                        start()
                    }
                }
            }
            return
        }
        adapterWaitAttempt = 0

        leAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        leScanner = bluetoothAdapter?.bluetoothLeScanner

        // 1. Setup GATT Server (Peripheral mode)
        setupGattServer()

        // 2. Start BLE Advertising
        startAdvertising()

        // 3. Start scanning for nearby DeadNet nodes
        startScanningLoop()
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!_isRunning) return
        _isRunning = false
        log("Stopping BLE Ambient Transport...")

        scanJob?.cancel()
        scanJob = null

        stopAdvertising()
        stopScanning()

        // Disconnect all client connections
        connectedGattClients.values.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {}
        }
        connectedGattClients.clear()

        // Close GATT server
        try {
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null

        incomingBuffers.clear()
        discoveredPeersMap.clear()
        _peersFlow.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendPacket(packet: DeadNetPacket, targetPeer: PeerNode?): Boolean {
        val packetBytes = packet.toByteArray()
        log("Queueing packet ${packet.msgId} (${packetBytes.size} bytes) for BLE dispatch")

        // 1. Notify connected central devices via GATT server (peripheral → central)
        val char = packetCharacteristic
        val server = gattServer
        if (server != null && char != null) {
            val chunks = sliceIntoChunks(packetBytes, BleConstants.DEFAULT_BLE_CHUNK_SIZE)
            // Broadcast each chunk to every subscribed central before moving to the
            // next chunk, so latency doesn't scale linearly with the number of centrals.
            val centrals = subscribedCentrals.toList()
            for (chunk in chunks) {
                char.value = chunk
                for (addr in centrals) {
                    val device = bluetoothAdapter?.getRemoteDevice(addr) ?: continue
                    server.notifyCharacteristicChanged(device, char, false)
                }
                kotlinx.coroutines.delay(15) // Inter-chunk pacing
            }
        }

        // 2. Dispatch to connected GATT client connections (central → peripheral)
        //    Serialized per device so concurrent sendPacket calls never interleave
        //    this packet's chunks with another packet's chunks.
        connectedGattClients.values.forEach { gatt ->
            val addr = gatt.device.address
            val mutex = perDeviceWriteMutex.getOrPut(addr) { Mutex() }
            scope.launch(Dispatchers.IO) {
                mutex.withLock { sendPacketToGatt(gatt, packetBytes) }
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendPacketToGatt(gatt: BluetoothGatt, bytes: ByteArray) {
        val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(BleConstants.PACKET_CHARACTERISTIC_UUID) ?: return

        val chunks = sliceIntoChunks(bytes, BleConstants.DEFAULT_BLE_CHUNK_SIZE)
        for (chunk in chunks) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            delay(15) // Brief inter-chunk pacing for BLE stack
        }
    }

    // ── GATT Server (Peripheral Mode) ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        val mgr = bluetoothManager ?: return

        gattServer = mgr.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val addr = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("BLE Central connected: $addr")
                    onHandshakeReady?.invoke()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("BLE Central disconnected: $addr")
                    subscribedCentrals.remove(addr)
                    incomingBuffers.remove(addr)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (characteristic.uuid == BleConstants.PACKET_CHARACTERISTIC_UUID && value != null) {
                    handleIncomingBytes(device.address, value)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                // Handle CCCD subscription for notifications
                if (descriptor.uuid == BleConstants.CCCD_UUID && value != null && value.size >= 2) {
                    val enabled = (value[0].toInt() and 0xFF) == 0x01
                    val addr = device.address
                    if (enabled) {
                        subscribedCentrals.add(addr)
                        log("Central $addr subscribed to notifications")
                    } else {
                        subscribedCentrals.remove(addr)
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                log("MTU updated for ${device.address}: $mtu")
            }
        })

        // Build DeadNet Service & Characteristic
        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            BleConstants.PACKET_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        packetCharacteristic = characteristic

        // Add CCCD descriptor so centrals can subscribe to notifications
        val cccdDescriptor = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccdDescriptor)

        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
        log("BLE GATT Server initialized with Service: ${BleConstants.SERVICE_UUID}")
    }

    // ── BLE Advertising ───────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("BLE Advertising started successfully (Node: ${ownKeyPair.nodeId})")
        }

        override fun onStartFailure(errorCode: Int) {
            log("BLE Advertising failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = leAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // Include DeadNet Service UUID + truncated Node ID in service data
        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val nodeIdBytes = ownKeyPair.publicKey.take(4).toByteArray()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, nodeIdBytes)
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            log("Error starting BLE advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            leAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
    }

    // ── BLE Scanning & Client Connection ──────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE Scan failed with code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val addr = device.address ?: return
        val rssi = result.rssi

        val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()
        val hasDeadNet = serviceUuids.any { it.uuid == BleConstants.SERVICE_UUID }
        if (!hasDeadNet) return

        val peerName = try { device.name ?: "Nearby BLE Node" } catch (_: Exception) { "Nearby BLE Node" }
        val id = addr.replace(":", "").takeLast(8).uppercase()

        val peer = PeerNode(
            id = id,
            name = peerName,
            transportType = TransportType.BLE,
            rssi = rssi,
            lastSeen = System.currentTimeMillis(),
            address = addr,
            isConnected = connectedGattClients.containsKey(addr),
            statusText = if (connectedGattClients.containsKey(addr)) "CONNECTED" else "AVAILABLE"
        )

        discoveredPeersMap[addr] = peer
        _peersFlow.value = discoveredPeersMap.values.toList()

        // Opportunistic auto-connect to discovered BLE peer if not already connected
        if (!connectedGattClients.containsKey(addr)) {
            connectToGatt(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToGatt(device: BluetoothDevice) {
        val addr = device.address
        if (connectedGattClients.containsKey(addr)) return

        // Throttle auto-connect so a persistently-scanning device doesn't spam
        // connect attempts while the link is mid-setup or repeatedly failing.
        val now = System.currentTimeMillis()
        val last = lastConnectionAttempt[addr] ?: 0L
        if (now - last < CONNECTION_ATTEMPT_INTERVAL_MS) return
        lastConnectionAttempt[addr] = now

        log("Connecting BLE GATT to $addr (${device.name ?: "Unknown"})...")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val addr = gatt.device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("BLE GATT connection reported status=$status; tearing down $addr")
                        cleanupGattClient(addr, gatt)
                        return
                    }
                    log("Connected to BLE GATT server: $addr. Discovering services...")
                    connectedGattClients[addr] = gatt
                    gatt.requestMtu(256)
                    gatt.discoverServices()

                    updatePeerState(addr, connected = true)
                    onHandshakeReady?.invoke()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Disconnected from BLE GATT: $addr")
                    cleanupGattClient(addr, gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Services discovered for $addr. Enabling notifications...")
                    val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
                    val char = service.getCharacteristic(BleConstants.PACKET_CHARACTERISTIC_UUID) ?: return
                    gatt.setCharacteristicNotification(char, true)
                    // Also write the CCCD descriptor so the remote GATT server knows
                    // we want notifications (setCharacteristicNotification alone is
                    // often insufficient for the peripheral to enable delivery).
                    val cccd = char.getDescriptor(BleConstants.CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                log("Client MTU updated for ${gatt.device.address}: $mtu (status=$status)")
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                log("Client write to $addr: ${characteristic.uuid} status=$status")
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                log("Client CCCD write to $addr: ${descriptor.uuid} status=$status")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == BleConstants.PACKET_CHARACTERISTIC_UUID) {
                    val value = characteristic.value
                    if (value != null) {
                        handleIncomingBytes(gatt.device.address, value)
                    }
                }
            }
        }

        // connectGatt must run on the main thread. The scan callback executes on a
        // binder thread, so hop to the main Looper before connecting.
        mainHandler.post {
            try {
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                if (gatt == null) {
                    // Failed to start the connect; allow a retry on a later scan.
                    lastConnectionAttempt.remove(addr)
                    log("connectGatt returned null for $addr")
                }
            } catch (e: Exception) {
                lastConnectionAttempt.remove(addr)
                log("connectGatt error for $addr: ${e.message}")
            }
        }
    }

    /** Remove a client GATT entry and safely close/unregister it. */
    private fun cleanupGattClient(addr: String, gatt: BluetoothGatt) {
        connectedGattClients.remove(addr)
        perDeviceWriteMutex.remove(addr)
        incomingBuffers.remove(addr)
        lastConnectionAttempt.remove(addr)
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}

        updatePeerState(addr, connected = false)
    }

    /** Refresh a discovered peer's connection status and re-emit the peers flow. */
    private fun updatePeerState(addr: String, connected: Boolean) {
        discoveredPeersMap[addr]?.let {
            discoveredPeersMap[addr] = it.copy(
                isConnected = connected,
                statusText = if (connected) "CONNECTED" else "AVAILABLE"
            )
            _peersFlow.value = discoveredPeersMap.values.toList()
        }
    }

    private fun startScanningLoop() {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isRunning) {
                startScanning()
                delay(12000) // Scan for 12 seconds
                stopScanning()
                delay(4000)  // 4 second duty-cycle pause to save battery
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val scanner = leScanner ?: return
        // No service-UUID scan filter: some BLE stacks omit the 128-bit UUID from
        // the top-level advertising field set, which makes an exact UUID filter
        // silently match nothing. We filter instead in handleScanResult by
        // checking serviceUuids (and fall back to service data) on each result.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            log("startScan error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        try {
            leScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    // ── Packet Framing & Reassembly ──────────────────────────────────────────

    private fun handleIncomingBytes(remoteAddress: String, chunk: ByteArray) {
        val buffer = incomingBuffers.getOrPut(remoteAddress) { ByteArrayOutputStream() }
        synchronized(buffer) {
            buffer.write(chunk)
            val currentBytes = buffer.toByteArray()

            // Check if we have at least the 92-byte header
            if (currentBytes.size >= DeadNetPacket.HEADER_SIZE) {
                // Header layout: payloadLength is at offset 90 (2 bytes)
                val payloadLen = ByteBuffer.wrap(currentBytes, 90, 2).short.toInt() and 0xFFFF
                val fullPacketSize = DeadNetPacket.HEADER_SIZE + payloadLen

                if (currentBytes.size >= fullPacketSize) {
                    try {
                        val packetBytes = currentBytes.copyOfRange(0, fullPacketSize)
                        val packet = DeadNetPacket.fromByteArray(packetBytes)
                        log("Assembled complete packet from BLE $remoteAddress (${packet.msgId}, type ${packet.packetType})")
                        onPacketReceived?.invoke(packet)

                        // Clear assembled packet bytes from buffer
                        buffer.reset()
                        if (currentBytes.size > fullPacketSize) {
                            buffer.write(currentBytes.copyOfRange(fullPacketSize, currentBytes.size))
                        }
                    } catch (e: Exception) {
                        log("BLE packet parse error: ${e.message}")
                        buffer.reset()
                    }
                }
            }
        }
    }

    private fun sliceIntoChunks(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            chunks.add(bytes.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    private fun log(message: String) {
        onLogEvent?.invoke("[BLE] $message")
    }
}
