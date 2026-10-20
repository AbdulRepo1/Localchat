package com.example.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages device audio routing between the bottom loudspeaker and top earpiece.
 * Uses Android 12+ (API 31) communication device APIs with backwards compatibility.
 */
class AudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isSpeakerphoneOn = MutableStateFlow(false)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    fun setupCallAudio(defaultToSpeaker: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(defaultToSpeaker)
    }

    fun teardownCallAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        _isSpeakerphoneOn.value = false
    }

    fun setSpeakerphone(on: Boolean) {
        _isSpeakerphoneOn.value = on

        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val availableDevices = audioManager.availableCommunicationDevices
            if (on) {
                val speaker = availableDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                val earpiece = availableDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
    }
}
