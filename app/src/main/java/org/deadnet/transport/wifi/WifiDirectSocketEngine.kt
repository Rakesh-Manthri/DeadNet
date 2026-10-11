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
 * - Group Owner (GO): Runs ServerSocket on port 8888 (192.168.49.1 / 0.0.0.0).
 * - Group Client: Connects via Socket to GO address on port 8888.
 * - Bidirectional framed packet exchange.
 * - Disconnect callback once packet exchange completes to resume peer discovery.
 */
class WifiDirectSocketEngine(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val onPacketReceived: (DeadNetPacket) -> Unit,
    private val onLog: (String) -> Unit,
    private val onExchangeCompleted: () -> Unit
) {
    companion object {
        const val DEFAULT_PORT = 8888
        const val SOCKET_TIMEOUT_MS = 10000
    }

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var workerJob: Job? = null

    private val outboundQueue = ConcurrentLinkedQueue<DeadNetPacket>()

    fun queueOutboundPacket(packet: DeadNetPacket) {
        outboundQueue.add(packet)
    }

    fun startServer() {
        stop()
        onLog("Starting Wi-Fi Direct ServerSocket on port $port...")
        workerJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                onLog("ServerSocket listening on port $port. Awaiting client connection...")

                while (isActive) {
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        if (!isActive) break
                        onLog("ServerSocket accept error: ${e.message}")
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
        workerJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var connectedSocket: Socket? = null
            while (isActive && attempt < 3 && connectedSocket == null) {
                attempt++
                try {
                    val socket = Socket()
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.connect(InetSocketAddress(hostAddress, port), SOCKET_TIMEOUT_MS)
                    connectedSocket = socket
                    onLog("Successfully connected to GO at $hostAddress:$port (attempt $attempt)")
                } catch (e: IOException) {
                    onLog("Connection attempt $attempt failed: ${e.message}")
                    kotlinx.coroutines.delay(1000)
                }
            }

            if (connectedSocket != null) {
                handleSocketSession(connectedSocket)
            } else {
                onLog("Failed to establish socket connection to GO after 3 attempts.")
                onExchangeCompleted()
            }
        }
    }

    private suspend fun handleSocketSession(socket: Socket) = withContext(Dispatchers.IO) {
        activeSocket = socket
        socket.soTimeout = SOCKET_TIMEOUT_MS
        var packetsExchanged = 0

        try {
            val inputStream = BufferedInputStream(socket.getInputStream())
            val outputStream = BufferedOutputStream(socket.getOutputStream())

            // 1. Drain and transmit all queued outbound packets
            while (!outboundQueue.isEmpty()) {
                val packet = outboundQueue.poll() ?: break
                onLog("Sending packet ${packet.msgId} (type ${packet.packetType}) via Wi-Fi socket")
                packet.writeTo(outputStream)
                outputStream.flush()
                packetsExchanged++
            }

            // 2. Read incoming packets until socket closed or timeout
            while (isActive && !socket.isClosed) {
                try {
                    val packet = DeadNetPacket.fromInputStream(inputStream)
                    onLog("Received packet ${packet.msgId} (type ${packet.packetType}, ${packet.payload.size} bytes) via Wi-Fi socket")
                    onPacketReceived(packet)
                    packetsExchanged++
                    // For Stage 1 burst exchange, single packet or queue drainage is completed
                    break
                } catch (e: EOFException) {
                    onLog("Socket EOF reached.")
                    break
                } catch (e: IOException) {
                    // Timeout or read failure
                    break
                }
            }
        } catch (e: Exception) {
            onLog("Socket session error: ${e.message}")
        } finally {
            onLog("Socket session completed ($packetsExchanged packets exchanged). Cleaning up...")
            closeQuietly(socket)
            activeSocket = null
            onExchangeCompleted()
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
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
