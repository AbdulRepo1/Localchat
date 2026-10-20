package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import javax.crypto.SecretKey

/**
 * Encapsulates AudioTrack hardware playback in STREAM mode.
 * Filters out-of-order UDP packets using sequence number checks.
 * Supports AES-GCM authenticated payload decryption.
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    var isPlaying = false
        private set

    private var lastSequenceNumber = -1

    fun start(): Boolean {
        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_OUT,
            AudioConfig.ENCODING
        )

        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            Log.e("AudioPlayer", "Invalid AudioTrack buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize, AudioConfig.FRAME_SIZE_BYTES * 4)

        return try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioConfig.ENCODING)
                        .setSampleRate(AudioConfig.SAMPLE_RATE)
                        .setChannelMask(AudioConfig.CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("AudioPlayer", "AudioTrack failed to initialize")
                release()
                return false
            }

            audioTrack?.play()
            isPlaying = true
            lastSequenceNumber = -1
            true
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error starting AudioTrack", e)
            release()
            false
        }
    }

    /**
     * Processes an incoming UDP packet, verifies sequence number, decrypts payload if key is supplied,
     * and streams PCM samples to the speaker.
     */
    fun writePacket(packetData: ByteArray, length: Int, secretKey: SecretKey? = null) {
        if (!isPlaying || audioTrack == null || length < AudioConfig.HEADER_SIZE_BYTES) return

        val sequence = AudioPacketHelper.unpackSequence(packetData)
        // Discard out-of-order / late packets (unless sequence wrapped)
        if (lastSequenceNumber != -1 && sequence < lastSequenceNumber && (lastSequenceNumber - sequence) < 1000) {
            return
        }
        lastSequenceNumber = sequence

        val payloadLength = length - AudioConfig.HEADER_SIZE_BYTES
        if (payloadLength <= 0) return

        if (secretKey != null) {
            // Decrypt with AES-GCM
            val decrypted = CryptoHelper.decrypt(
                encryptedBytes = packetData,
                offset = AudioConfig.HEADER_SIZE_BYTES,
                length = payloadLength,
                key = secretKey
            )
            if (decrypted != null && decrypted.isNotEmpty()) {
                audioTrack?.write(decrypted, 0, decrypted.size)
            }
        } else {
            // Plain PCM fallback (for backwards compatibility)
            audioTrack?.write(packetData, AudioConfig.HEADER_SIZE_BYTES, payloadLength)
        }
    }

    fun stop() {
        try {
            if (isPlaying) {
                audioTrack?.stop()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error stopping AudioTrack", e)
        } finally {
            isPlaying = false
        }
    }

    fun release() {
        stop()
        try {
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        lastSequenceNumber = -1
    }
}
