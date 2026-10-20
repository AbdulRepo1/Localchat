package com.example.data.repository

import com.example.ChatMessage
import com.example.data.local.dao.MessageDao
import com.example.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository mediating between the local Room database and UI/Domain layers.
 * Follows the Single Source of Truth principle.
 */
class ChatRepository(
    private val messageDao: MessageDao
) {

    fun getMessagesForPeer(peerName: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesForPeer(peerName).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    fun getAllMessages(): Flow<List<ChatMessage>> {
        return messageDao.getAllMessages().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    suspend fun saveMessage(peerName: String, text: String, isMine: Boolean) {
        val entity = MessageEntity(
            peerName = peerName,
            text = text,
            isMine = isMine,
            timestamp = System.currentTimeMillis()
        )
        messageDao.insertMessage(entity)
    }

    suspend fun clearHistory(peerName: String) {
        messageDao.deleteMessagesForPeer(peerName)
    }

    suspend fun clearAll() {
        messageDao.deleteAllMessages()
    }

    private fun MessageEntity.toDomainModel(): ChatMessage {
        return ChatMessage(
            id = this.id,
            isMine = this.isMine,
            text = this.text,
            timestamp = this.timestamp,
            peerName = this.peerName
        )
    }
}
