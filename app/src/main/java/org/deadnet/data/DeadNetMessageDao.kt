package org.deadnet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeadNetMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: DeadNetMessage)

    @Update
    suspend fun update(message: DeadNetMessage)

    @Query("SELECT * FROM dead_net_messages WHERE msgId = :msgId LIMIT 1")
    suspend fun getMessageById(msgId: String): DeadNetMessage?

    @Query("SELECT * FROM dead_net_messages ORDER BY createdAt DESC")
    fun getAllMessagesFlow(): Flow<List<DeadNetMessage>>

    @Query("SELECT * FROM dead_net_messages WHERE tokensRemaining > 1 AND isDelivered = 0")
    suspend fun getForwardableMessages(): List<DeadNetMessage>

    @Query("SELECT * FROM dead_net_messages WHERE tokensRemaining = 1 AND isDelivered = 0")
    suspend fun getWaitPhaseMessages(): List<DeadNetMessage>

    @Query("UPDATE dead_net_messages SET isDelivered = 1 WHERE msgId = :msgId")
    suspend fun markDelivered(msgId: String): Int

    @Query("UPDATE dead_net_messages SET tokensRemaining = :newTokens WHERE msgId = :msgId")
    suspend fun updateTokens(msgId: String, newTokens: Int): Int

    @Query("DELETE FROM dead_net_messages WHERE ttlExpiresAt < :currentTimeMillis")
    suspend fun deleteExpired(currentTimeMillis: Long): Int

    @Query("SELECT COUNT(*) FROM dead_net_messages")
    suspend fun getMessageCount(): Int
}
