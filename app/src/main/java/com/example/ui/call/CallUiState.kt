package com.example.ui.call

import com.example.CallState
import com.example.Peer

/**
 * Immutable UI state for the Active / Ringing Call overlay.
 */
data class CallUiState(
    val callState: CallState = CallState.IDLE,
    val connectedPeer: Peer? = null,
    val isSpeakerphoneOn: Boolean = false,
    val isMuted: Boolean = false
)
