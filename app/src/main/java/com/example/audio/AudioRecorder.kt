package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Encapsulates AudioRecord hardware capture.
 * Uses VOICE_COMMUNICATION source for hardware Acoustic Echo Cancellation (AEC) and Noise Suppression (NS).
 */
class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set

    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioRecorder", "RECORD_AUDIO permission not granted")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_IN,
            AudioConfig.ENCODING
        )

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e("AudioRecorder", "Invalid AudioRecord buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize, AudioConfig.FRAME_SIZE_BYTES * 2)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_IN,
                AudioConfig.ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord failed to initialize")
                release()
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting AudioRecord", e)
            release()
            false
        }
    }

    fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        if (!isRecording || audioRecord == null) return -1
        return audioRecord?.read(buffer, offset, size) ?: -1
    }

    fun stop() {
        try {
            if (isRecording) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping AudioRecord", e)
        } finally {
            isRecording = false
        }
    }

    fun release() {
        stop()
        try {
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }
}
