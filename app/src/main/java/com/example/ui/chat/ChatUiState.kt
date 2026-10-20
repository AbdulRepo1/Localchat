package com.example.ui.chat

import com.example.ChatMessage
import com.example.ConnectionState
import com.example.Peer

/**
 * Immutable UI state for the Chat screen.
 * Follows Unidirectional Data Flow (UDF) best practices.
 */
data class ChatUiState(
    val connectionState: ConnectionState = ConnectionState.SEARCHING,
    val connectedPeer: Peer? = null,
    val discoveredPeers: List<Peer> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val messageText: String = "",
    val showPeersDialog: Boolean = false,
    val autoIntercomEnabled: Boolean = true,
    val localIps: List<String> = emptyList()
)
