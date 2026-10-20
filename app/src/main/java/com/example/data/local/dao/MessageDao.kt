package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for Message table queries.
 * Returns reactive Kotlin Flows that auto-emit on database updates.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE peerName = :peerName ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerName: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE peerName = :peerName")
    suspend fun deleteMessagesForPeer(peerName: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
