package org.deadnet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_packets")
data class SeenPacket(
    @PrimaryKey
    val msgId: String,
    val receivedAt: Long
)
