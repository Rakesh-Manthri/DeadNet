package org.deadnet.data

/**
 * Tracks an in-progress file transfer session (send or receive).
 *
 * @param sessionId    Unique ID shared between sender and receiver.
 * @param fileName     Human-readable file name (e.g. "photo.jpg").
 * @param mimeType     MIME type (e.g. "image/jpeg", "application/pdf").
 * @param totalSize    Total bytes of the file.
 * @param totalChunks  Number of chunks the file is split into.
 * @param direction    SEND if we are the sender, RECEIVE if we are the receiver.
 * @param peerId       NodeId of the peer on the other side.
 * @param peerName     Display name of the peer.
 * @param bytesTransferred How many bytes have been sent/received so far.
 * @param isComplete   True when all chunks have been sent/received.
 * @param isFailed     True if the transfer was aborted or timed out.
 * @param savedPath    After completion (RECEIVE), the absolute path of the saved file.
 */
data class FileTransferSession(
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val totalSize: Long,
    val totalChunks: Int,
    val direction: TransferDirection,
    val peerId: String,
    val peerName: String,
    val bytesTransferred: Long = 0L,
    val isComplete: Boolean = false,
    val isFailed: Boolean = false,
    val savedPath: String? = null,
    val receivedChunks: MutableSet<Int> = mutableSetOf()
) {
    val progressFraction: Float
        get() = if (totalSize == 0L) 0f else (bytesTransferred.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)

    val progressPercent: Int get() = (progressFraction * 100).toInt()

    val statusLabel: String get() = when {
        isComplete -> "Complete"
        isFailed -> "Failed"
        direction == TransferDirection.SEND -> "Sending ${progressPercent}%"
        else -> "Receiving ${progressPercent}%"
    }
}

enum class TransferDirection { SEND, RECEIVE }
