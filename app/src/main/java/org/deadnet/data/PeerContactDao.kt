package org.deadnet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: PeerContact)

    @Query("SELECT * FROM peer_contacts")
    fun getAllContactsFlow(): Flow<List<PeerContact>>

    @Query("SELECT * FROM peer_contacts WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getContact(nodeId: String): PeerContact?

    @Query("DELETE FROM peer_contacts WHERE nodeId = :nodeId")
    suspend fun deleteContact(nodeId: String): Int

    @Query("DELETE FROM peer_contacts")
    suspend fun clearAll(): Int
}
