package com.example.audio

import android.media.AudioFormat

/**
 * Audio pipeline specifications, cryptographic overhead, and buffer constants.
 * Standardized to 16kHz Mono 16-bit PCM (VoIP standard).
 */
object AudioConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // 20ms of audio frame at 16kHz, 16-bit mono = 16000 * 2 bytes * 0.02s = 640 bytes
    const val FRAME_SIZE_BYTES = 640
    const val HEADER_SIZE_BYTES = 4 // 32-bit integer for packet sequence number
    const val CRYPTO_OVERHEAD_BYTES = 12 + 16 // 12-byte IV + 16-byte GCM authentication tag
    const val PACKET_BUFFER_SIZE = FRAME_SIZE_BYTES + HEADER_SIZE_BYTES + CRYPTO_OVERHEAD_BYTES + 32
}
