package org.deadnet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeenPacketDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seenPacket: SeenPacket): Long

    @Query("SELECT COUNT(*) > 0 FROM seen_packets WHERE msgId = :msgId")
    suspend fun hasSeen(msgId: String): Boolean

    @Query("DELETE FROM seen_packets WHERE receivedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM seen_packets")
    suspend fun getSeenCount(): Int
}
