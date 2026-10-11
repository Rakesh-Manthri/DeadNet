package org.deadnet.transport

import kotlinx.coroutines.flow.StateFlow
import org.deadnet.protocol.DeadNetPacket

interface TransportLayer {
    val transportType: TransportType
    val isRunning: Boolean
    val peersFlow: StateFlow<List<PeerNode>>

    var onPacketReceived: ((DeadNetPacket) -> Unit)?
    var onLogEvent: ((String) -> Unit)?
    var onHandshakeReady: (() -> Unit)?

    fun start()
    fun stop()
    suspend fun sendPacket(packet: DeadNetPacket, targetPeer: PeerNode? = null): Boolean
}
