package org.deadnet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores user-assigned nicknames and metadata for known DeadNet mesh nodes.
 */
@Entity(tableName = "peer_contacts")
data class PeerContact(
    @PrimaryKey
    val nodeId: String,
    val alias: String,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
