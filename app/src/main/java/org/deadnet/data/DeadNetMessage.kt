package org.deadnet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.deadnet.crypto.toHexString

enum class DeliveryStatus {
    STORED_LOCALLY,
    CARRIED,
    DELIVERED
}

@Entity(tableName = "dead_net_messages")
data class DeadNetMessage(
    @PrimaryKey
    val msgId: String,
    val senderPubKey: ByteArray,
    val recipientPubKey: ByteArray,
    val ciphertext: ByteArray,
    val tokensRemaining: Int,
    val hopCount: Int,
    val createdAt: Long,
    val ttlExpiresAt: Long,
    val isDelivered: Boolean = false,
    val isBroadcast: Boolean = false,
    val decryptedText: String? = null,
    val isOutgoing: Boolean = false,
    val conversationId: String = "BROADCAST",
    val conversationTitle: String = "Mesh Broadcast"
) {
    val deliveryStatus: DeliveryStatus
        get() = when {
            isDelivered -> DeliveryStatus.DELIVERED
            hopCount > 0 -> DeliveryStatus.CARRIED
            else -> DeliveryStatus.STORED_LOCALLY
        }

    val senderNodeId: String
        get() = if (senderPubKey.isNotEmpty()) senderPubKey.toHexString().take(8) else "UNKNOWN"

    val recipientNodeId: String
        get() = if (isBroadcast) "BROADCAST" else if (recipientPubKey.isNotEmpty()) recipientPubKey.toHexString().take(8) else "UNKNOWN"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeadNetMessage

        if (msgId != other.msgId) return false
        if (!senderPubKey.contentEquals(other.senderPubKey)) return false
        if (!recipientPubKey.contentEquals(other.recipientPubKey)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (tokensRemaining != other.tokensRemaining) return false
        if (hopCount != other.hopCount) return false
        if (createdAt != other.createdAt) return false
        if (ttlExpiresAt != other.ttlExpiresAt) return false
        if (isDelivered != other.isDelivered) return false
        if (isBroadcast != other.isBroadcast) return false
        if (decryptedText != other.decryptedText) return false
        if (isOutgoing != other.isOutgoing) return false
        if (conversationId != other.conversationId) return false
        if (conversationTitle != other.conversationTitle) return false

        return true
    }

    override fun hashCode(): Int {
        var result = msgId.hashCode()
        result = 31 * result + senderPubKey.contentHashCode()
        result = 31 * result + recipientPubKey.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + tokensRemaining
        result = 31 * result + hopCount
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + ttlExpiresAt.hashCode()
        result = 31 * result + isDelivered.hashCode()
        result = 31 * result + isBroadcast.hashCode()
        result = 31 * result + (decryptedText?.hashCode() ?: 0)
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + conversationTitle.hashCode()
        return result
    }
}
