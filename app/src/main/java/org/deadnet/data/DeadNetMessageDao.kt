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

    @Query("SELECT * FROM dead_net_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversationFlow(convId: String): Flow<List<DeadNetMessage>>

    @Query("SELECT * FROM dead_net_messages WHERE tokensRemaining > 1 AND isDelivered = 0")
    suspend fun getForwardableMessages(): List<DeadNetMessage>

    @Query("SELECT * FROM dead_net_messages WHERE tokensRemaining = 1 AND isDelivered = 0")
    suspend fun getWaitPhaseMessages(): List<DeadNetMessage>

    @Query("UPDATE dead_net_messages SET isDelivered = 1 WHERE msgId = :msgId")
    suspend fun markDelivered(msgId: String): Int

    @Query("UPDATE dead_net_messages SET tokensRemaining = :newTokens WHERE msgId = :msgId")
    suspend fun updateTokens(msgId: String, newTokens: Int): Int

    @Query("DELETE FROM dead_net_messages WHERE msgId = :msgId")
    suspend fun deleteMessage(msgId: String): Int

    @Query("DELETE FROM dead_net_messages WHERE conversationId = :convId")
    suspend fun deleteConversation(convId: String): Int

    @Query("UPDATE dead_net_messages SET conversationId = :newConvId, conversationTitle = :newTitle WHERE conversationId = :oldConvId")
    suspend fun migrateConversation(oldConvId: String, newConvId: String, newTitle: String): Int

    @Query("SELECT * FROM dead_net_messages WHERE isBroadcast = 1 AND createdAt > :sinceMillis ORDER BY createdAt ASC")
    suspend fun getRecentBroadcastMessages(sinceMillis: Long): List<DeadNetMessage>

    @Query("SELECT * FROM dead_net_messages WHERE recipientPubKey = :recipientPub AND isDelivered = 0 ORDER BY createdAt ASC")
    suspend fun getPendingDirectMessagesForRecipient(recipientPub: ByteArray): List<DeadNetMessage>

    @Query("DELETE FROM dead_net_messages")
    suspend fun deleteAllMessages(): Int

    @Query("DELETE FROM dead_net_messages WHERE ttlExpiresAt < :currentTimeMillis")
    suspend fun deleteExpired(currentTimeMillis: Long): Int

    @Query("SELECT COUNT(*) FROM dead_net_messages")
    suspend fun getMessageCount(): Int
}
