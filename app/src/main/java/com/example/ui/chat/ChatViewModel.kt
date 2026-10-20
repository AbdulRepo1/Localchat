package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.NetworkManager
import com.example.Peer
import com.example.ConnectionState
import com.example.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class ChatViewModel(
    private val networkManager: NetworkManager
) : ViewModel() {

    private val _messageText = MutableStateFlow("")
    private val _showPeersDialog = MutableStateFlow(false)

//    val uiState: StateFlow<ChatUiState> = combine(
//        networkManager.connectionState,
//        networkManager.connectedPeer,
//        networkManager.discoveredPeers,
//        networkManager.messages,
//        networkManager.autoIntercomEnabled,
//        _messageText,
//        _showPeersDialog
//    ) { connectionState, connectedPeer, discoveredPeers, messages, autoIntercom, text, showDialog ->
//        ChatUiState(
//            connectionState = connectionState,
//            connectedPeer = connectedPeer,
//            discoveredPeers = discoveredPeers,
//            messages = messages,
//            messageText = text,
//            showPeersDialog = showDialog,
//            autoIntercomEnabled = autoIntercom,
//            localIps = networkManager.getLocalIpAddress()
//        )
//    }.stateIn(
//        scope = viewModelScope,
//        started = SharingStarted.WhileSubscribed(5000),
//        initialValue = ChatUiState()
//    )

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ChatUiState> = combine(
        networkManager.connectionState,
        networkManager.connectedPeer,
        networkManager.discoveredPeers,
        networkManager.messages,
        networkManager.autoIntercomEnabled,
        _messageText,
        _showPeersDialog
    ) { args: Array<Any?> ->
        ChatUiState(
            connectionState = args[0] as ConnectionState,
            connectedPeer = args[1] as Peer?,
            discoveredPeers = args[2] as List<Peer>,
            messages = args[3] as List<ChatMessage>,
            autoIntercomEnabled = args[4] as Boolean,
            messageText = args[5] as String,
            showPeersDialog = args[6] as Boolean,
            localIps = networkManager.getLocalIpAddress()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )
    fun startHosting() {
        networkManager.startHosting()
    }

    fun onMessageTextChanged(newText: String) {
        _messageText.value = newText
    }

    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isNotEmpty()) {
            viewModelScope.launch {
                networkManager.sendMessage(text)
                _messageText.value = ""
            }
        }
    }

    fun connectToPeer(peer: Peer) {
        viewModelScope.launch {
            networkManager.connectToPeer(peer)
            _showPeersDialog.value = false
        }
    }

    fun disconnect() {
        networkManager.resetConnection()
    }

    fun setPeersDialogVisible(visible: Boolean) {
        _showPeersDialog.value = visible
    }

    fun toggleAutoIntercom() {
        val current = uiState.value.autoIntercomEnabled
        networkManager.setAutoIntercomEnabled(!current)
    }
}
