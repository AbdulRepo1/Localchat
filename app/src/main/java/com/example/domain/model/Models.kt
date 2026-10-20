package com.example.domain.model

import java.net.InetAddress
import java.util.UUID

/**
 * Domain entity representing a discovered device on the local Wi-Fi network.
 */
data class Peer(
    val name: String,
    val host: InetAddress,
    val port: Int
)

/**
 * Network Service Discovery & TCP connection lifecycle states.
 */
enum class ConnectionState {
    DISCONNECTED, SEARCHING, CONNECTED, DISCONNECTING
}

/**
 * Voice call states for the P2P intercom pipeline.
 */
enum class CallState {
    IDLE, INCOMING, OUTGOING, ACTIVE
}

/**
 * Domain entity representing a chat message.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isMine: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val peerName: String = ""
)
