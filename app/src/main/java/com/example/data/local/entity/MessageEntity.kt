package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room database table representing a persisted chat message.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val peerName: String,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
