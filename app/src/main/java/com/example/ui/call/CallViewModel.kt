package com.example.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.CallState
import com.example.NetworkManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallViewModel(
    private val networkManager: NetworkManager
) : ViewModel() {

    val uiState: StateFlow<CallUiState> = combine(
        networkManager.callState,
        networkManager.connectedPeer,
        networkManager.audioStreamer.isSpeakerphoneOn
    ) { callState, peer, isSpeakerOn ->
        CallUiState(
            callState = callState,
            connectedPeer = peer,
            isSpeakerphoneOn = isSpeakerOn,
            isMuted = networkManager.audioStreamer.isMuted
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CallUiState()
    )

    fun initiateCall() {
        viewModelScope.launch {
            networkManager.sendCallRequest()
        }
    }

    fun acceptCall() {
        viewModelScope.launch {
            networkManager.acceptCall()
        }
    }

    fun rejectCall() {
        viewModelScope.launch {
            networkManager.rejectCall()
        }
    }

    fun endCall() {
        viewModelScope.launch {
            networkManager.endCall()
        }
    }

    fun toggleSpeakerphone() {
        val currentSpeaker = uiState.value.isSpeakerphoneOn
        networkManager.audioStreamer.toggleSpeakerphone(!currentSpeaker, isUserAction = true)
    }
}
