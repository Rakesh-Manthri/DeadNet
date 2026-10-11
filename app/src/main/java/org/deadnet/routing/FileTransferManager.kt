package org.deadnet.routing

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.deadnet.data.FileTransferSession
import org.deadnet.data.TransferDirection
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.protocol.PacketType
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportLayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages peer-to-peer file transfers over the DeadNet mesh protocol.
 *
 * Wire format:
 * - FILE_METADATA packet payload:
 *     [sessionId: 36 UTF-8 bytes] [totalChunks: 4B int] [totalSize: 8B long]
 *     [mimeTypeLen: 2B short] [mimeType: UTF-8] [fileNameLen: 2B short] [fileName: UTF-8]
 *
 * - FILE_CHUNK packet payload:
 *     [sessionId: 36 UTF-8 bytes] [chunkIndex: 4B int] [chunkData: variable]
 *
 * Chunk size: 16 KB for rock-solid socket streaming without buffer overflows
 */
class FileTransferManager(
    private val context: Context,
    private val ownKeyPair: org.deadnet.crypto.DeadNetCrypto.KeyPair,
    private val scope: CoroutineScope
) {
    companion object {
        const val CHUNK_SIZE_BYTES = 16 * 1024  // 16 KB per chunk
        const val SESSION_ID_LEN = 36  // UUID string length
    }

    private val _activeSessions = MutableStateFlow<Map<String, FileTransferSession>>(emptyMap())
    val activeSessions: StateFlow<Map<String, FileTransferSession>> = _activeSessions.asStateFlow()

    // Buffers for incoming file chunks, keyed by sessionId
    private val chunkBuffers = ConcurrentHashMap<String, Array<ByteArray?>>()

    // Registered transports for outbound send
    private val transports = mutableListOf<TransportLayer>()

    fun registerTransport(transport: TransportLayer) {
        transports.add(transport)
    }

    /** Called by DtnRoutingEngine when a FILE_METADATA or FILE_CHUNK packet arrives. */
    fun handleIncomingPacket(packet: DeadNetPacket) {
        scope.launch(Dispatchers.IO) {
            when (packet.msgType) {
                PacketType.FILE_METADATA.code -> handleMetadata(packet)
                PacketType.FILE_CHUNK.code    -> handleChunk(packet)
            }
        }
    }

    /**
     * Sends a file to a specific peer.
     * @param fileUri   Content URI from the file picker.
     * @param peer      Target peer (must have public key from handshake).
     * @param onError   Called on any error with a human-readable message.
     */
    fun sendFile(fileUri: Uri, peer: PeerNode, onError: (String) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val fileName = resolveFileName(fileUri)
                val mimeType = cr.getType(fileUri) ?: "application/octet-stream"
                val bytes = cr.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: run { onError("Cannot read file"); return@launch }

                val sessionId = UUID.randomUUID().toString()
                val totalSize = bytes.size.toLong()
                val chunks = bytes.toChunks(CHUNK_SIZE_BYTES)
                val totalChunks = chunks.size

                // Record session
                val session = FileTransferSession(
                    sessionId = sessionId,
                    fileName = fileName,
                    mimeType = mimeType,
                    totalSize = totalSize,
                    totalChunks = totalChunks,
                    direction = TransferDirection.SEND,
                    peerId = peer.nodeId,
                    peerName = peer.name
                )
                updateSession(session)

                // 1. Send FILE_METADATA
                val metaPayload = encodeMetadata(sessionId, totalChunks, totalSize, mimeType, fileName)
                val metaPacket = DeadNetPacket(
                    msgType = PacketType.FILE_METADATA.code,
                    msgId = UUID.randomUUID(),
                    senderPubKey = ownKeyPair.publicKey,
                    recipientPubKey = peer.publicKey,
                    ttl = (System.currentTimeMillis() / 1000L) + 3600,
                    hopCount = 0,
                    tokensRemaining = 0,
                    payload = metaPayload
                )
                transports.forEach { it.sendPacket(metaPacket) }

                // Small pause after metadata so receiver can set up buffer
                delay(60)

                // 2. Send each FILE_CHUNK with pacing and progress tracking
                chunks.forEachIndexed { index, chunkData ->
                    val chunkPayload = encodeChunk(sessionId, index, chunkData)
                    val chunkPacket = DeadNetPacket(
                        msgType = PacketType.FILE_CHUNK.code,
                        msgId = UUID.randomUUID(),
                        senderPubKey = ownKeyPair.publicKey,
                        recipientPubKey = peer.publicKey,
                        ttl = (System.currentTimeMillis() / 1000L) + 3600,
                        hopCount = 0,
                        tokensRemaining = 0,
                        payload = chunkPayload
                    )
                    transports.forEach { it.sendPacket(chunkPacket) }

                    val sent = ((index + 1).toLong() * CHUNK_SIZE_BYTES).coerceAtMost(totalSize)
                    updateSession(session.copy(
                        bytesTransferred = sent,
                        isComplete = (index == totalChunks - 1)
                    ))

                    // Brief 10ms yield to prevent TCP buffer congestion
                    delay(10)
                }
            } catch (e: Exception) {
                onError("File transfer failed: ${e.message}")
            }
        }
    }

    // ── Internal Handlers ────────────────────────────────────────────────────

    private fun handleMetadata(packet: DeadNetPacket) {
        try {
            val (sessionId, totalChunks, totalSize, mimeType, fileName) = decodeMetadata(packet.payload)
            val senderNodeId = org.deadnet.crypto.DeadNetCrypto.deriveNodeId(packet.senderPubKey)

            val session = FileTransferSession(
                sessionId = sessionId,
                fileName = fileName,
                mimeType = mimeType,
                totalSize = totalSize,
                totalChunks = totalChunks,
                direction = TransferDirection.RECEIVE,
                peerId = senderNodeId,
                peerName = "Peer"
            )
            chunkBuffers[sessionId] = arrayOfNulls(totalChunks)
            updateSession(session)
        } catch (e: Exception) {
            // Malformed metadata — ignore
        }
    }

    private fun handleChunk(packet: DeadNetPacket) {
        try {
            val (sessionId, chunkIndex, chunkData) = decodeChunk(packet.payload)
            val session = _activeSessions.value[sessionId] ?: return
            val buffer = chunkBuffers[sessionId] ?: return

            buffer[chunkIndex] = chunkData
            session.receivedChunks.add(chunkIndex)

            val receivedBytes = session.receivedChunks.size.toLong() * CHUNK_SIZE_BYTES
            val updated = session.copy(bytesTransferred = receivedBytes.coerceAtMost(session.totalSize))
            updateSession(updated)

            // Check completion
            if (session.receivedChunks.size == session.totalChunks) {
                assembleAndSave(sessionId, session, buffer)
            }
        } catch (e: Exception) {
            // Malformed chunk — ignore
        }
    }

    private fun assembleAndSave(sessionId: String, session: FileTransferSession, buffer: Array<ByteArray?>) {
        try {
            val assembled = buffer.filterNotNull().reduce { acc, bytes -> acc + bytes }
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val outFile = File(dir, session.fileName)
            outFile.writeBytes(assembled)
            chunkBuffers.remove(sessionId)

            updateSession(session.copy(
                bytesTransferred = session.totalSize,
                isComplete = true,
                savedPath = outFile.absolutePath
            ))
        } catch (e: Exception) {
            updateSession(session.copy(isFailed = true))
        }
    }

    private fun updateSession(session: FileTransferSession) {
        _activeSessions.value = _activeSessions.value + (session.sessionId to session)
    }

    // ── Encoding Helpers ─────────────────────────────────────────────────────

    private fun encodeMetadata(
        sessionId: String,
        totalChunks: Int,
        totalSize: Long,
        mimeType: String,
        fileName: String
    ): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(SESSION_ID_LEN + 4 + 8 + 2 + mimeBytes.size + 2 + nameBytes.size)
        buf.put(sessionId.padEnd(SESSION_ID_LEN).take(SESSION_ID_LEN).toByteArray(Charsets.UTF_8))
        buf.putInt(totalChunks)
        buf.putLong(totalSize)
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        return buf.array()
    }

    private data class MetaFields(
        val sessionId: String,
        val totalChunks: Int,
        val totalSize: Long,
        val mimeType: String,
        val fileName: String
    )

    private fun decodeMetadata(payload: ByteArray): MetaFields {
        val buf = ByteBuffer.wrap(payload)
        val sessionIdBytes = ByteArray(SESSION_ID_LEN).also { buf.get(it) }
        val sessionId = String(sessionIdBytes, Charsets.UTF_8).trim()
        val totalChunks = buf.int
        val totalSize = buf.long
        val mimeLen = buf.short.toInt() and 0xFFFF
        val mimeBytes = ByteArray(mimeLen).also { buf.get(it) }
        val nameLen = buf.short.toInt() and 0xFFFF
        val nameBytes = ByteArray(nameLen).also { buf.get(it) }
        return MetaFields(sessionId, totalChunks, totalSize, String(mimeBytes), String(nameBytes))
    }

    private fun encodeChunk(sessionId: String, chunkIndex: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(SESSION_ID_LEN + 4 + data.size)
        buf.put(sessionId.padEnd(SESSION_ID_LEN).take(SESSION_ID_LEN).toByteArray(Charsets.UTF_8))
        buf.putInt(chunkIndex)
        buf.put(data)
        return buf.array()
    }

    private data class ChunkFields(val sessionId: String, val chunkIndex: Int, val data: ByteArray)

    private fun decodeChunk(payload: ByteArray): ChunkFields {
        val buf = ByteBuffer.wrap(payload)
        val sessionIdBytes = ByteArray(SESSION_ID_LEN).also { buf.get(it) }
        val sessionId = String(sessionIdBytes, Charsets.UTF_8).trim()
        val chunkIndex = buf.int
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        return ChunkFields(sessionId, chunkIndex, data)
    }

    private fun resolveFileName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            } ?: "file_${System.currentTimeMillis()}"
        } catch (_: Exception) {
            "file_${System.currentTimeMillis()}"
        }
    }

    private fun ByteArray.toChunks(chunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < size) {
            val end = (offset + chunkSize).coerceAtMost(size)
            chunks.add(copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }
}
