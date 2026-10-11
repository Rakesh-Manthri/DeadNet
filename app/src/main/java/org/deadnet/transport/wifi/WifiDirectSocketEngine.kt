package org.deadnet.transport.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.deadnet.protocol.DeadNetPacket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Socket Communications Engine for Wi-Fi Direct:
 * - Group Owner (GO): Runs ServerSocket on port 8888.
 * - Group Client: Connects via Socket to GO host address on port 8888.
 * - Bidirectional streaming and persistent session handling.
 */
class WifiDirectSocketEngine(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val onPacketReceived: (DeadNetPacket) -> Unit,
    private val onLog: (String) -> Unit,
    private val onConnectionClosed: () -> Unit
) {
    companion object {
        const val DEFAULT_PORT = 8888
        const val CONNECT_TIMEOUT_MS = 15000
    }

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var activeOutputStream: BufferedOutputStream? = null

    private val outboundQueue = ConcurrentLinkedQueue<DeadNetPacket>()
    var onSocketConnected: (() -> Unit)? = null

    val isConnected: Boolean
        get() = activeSocket?.isConnected == true && activeSocket?.isClosed == false

    fun queueOutboundPacket(packet: DeadNetPacket) {
        val stream = activeOutputStream
        if (stream != null && isConnected) {
            scope.launch(Dispatchers.IO) {
                try {
                    synchronized(stream) {
                        onLog("Transmitting packet ${packet.msgId} (type ${packet.packetType}) over active link...")
                        packet.writeTo(stream)
                        stream.flush()
                    }
                } catch (e: Exception) {
                    onLog("Socket write error: ${e.message}")
                    outboundQueue.add(packet)
                }
            }
        } else {
            outboundQueue.add(packet)
        }
    }

    fun startServer() {
        stop()
        onLog("Starting Wi-Fi Direct ServerSocket on port $port...")
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                onLog("ServerSocket active on port $port. Awaiting client connection...")

                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        if (!isActive) break
                        onLog("ServerSocket accept stopped: ${e.message}")
                        null
                    } ?: break

                    onLog("Client connected from ${client.inetAddress.hostAddress}")
                    handleSocketSession(client)
                }
            } catch (e: Exception) {
                if (isActive) {
                    onLog("ServerSocket exception: ${e.message}")
                }
            } finally {
                closeQuietly(serverSocket)
            }
        }
    }

    fun startClient(hostAddress: String) {
        stop()
        onLog("Connecting to Group Owner at $hostAddress:$port...")
        clientJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var connectedSocket: Socket? = null
            while (isActive && attempt < 4 && connectedSocket == null) {
                attempt++
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                    connectedSocket = socket
                    onLog("Connected to GO at $hostAddress:$port (attempt $attempt)")
                } catch (e: IOException) {
                    onLog("Connect attempt $attempt to $hostAddress failed: ${e.message}")
                    kotlinx.coroutines.delay(1500)
                }
            }

            if (connectedSocket != null) {
                handleSocketSession(connectedSocket)
            } else {
                onLog("Failed to reach Group Owner after $attempt attempts.")
                onConnectionClosed()
            }
        }
    }

    private suspend fun handleSocketSession(socket: Socket) = withContext(Dispatchers.IO) {
        activeSocket = socket
        try {
            val inputStream = BufferedInputStream(socket.getInputStream())
            val outputStream = BufferedOutputStream(socket.getOutputStream())
            activeOutputStream = outputStream

            // Notify that socket session is active and ready for handshake exchange
            onSocketConnected?.invoke()

            // Drain any pending outbound packets
            synchronized(outputStream) {
                while (!outboundQueue.isEmpty()) {
                    val packet = outboundQueue.poll() ?: break
                    onLog("Flushing queued packet ${packet.msgId} (type ${packet.packetType})")
                    packet.writeTo(outputStream)
                    outputStream.flush()
                }
            }

            // Continuous read loop: stream incoming packets
            while (isActive && !socket.isClosed) {
                try {
                    val packet = DeadNetPacket.fromInputStream(inputStream)
                    onLog("Received packet ${packet.msgId} (type ${packet.packetType}, ${packet.payload.size}B)")
                    onPacketReceived(packet)
                } catch (e: EOFException) {
                    onLog("Peer closed the connection (EOF).")
                    break
                } catch (e: IOException) {
                    if (isActive && !socket.isClosed) {
                        onLog("Socket I/O interrupted: ${e.message}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            onLog("Socket session error: ${e.message}")
        } finally {
            onLog("Socket session closed.")
            activeOutputStream = null
            closeQuietly(socket)
            activeSocket = null
            onConnectionClosed()
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null
        activeOutputStream = null
        closeQuietly(activeSocket)
        activeSocket = null
        closeQuietly(serverSocket)
        serverSocket = null
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {}
    }
}
