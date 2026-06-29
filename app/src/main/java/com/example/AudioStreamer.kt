package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.flow.asStateFlow
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class AudioStreamer(private val context: Context) : SensorEventListener {
    private var udpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playingJob: Job? = null
    var isMuted = false
    
    private val _isSpeakerphoneOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSpeakerphoneOn: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private var manualSpeakerOn: Boolean? = null
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    fun initSocket(): Int {
        try {
            udpSocket = DatagramSocket(0)
            return udpSocket?.localPort ?: 0
        } catch (e: Exception) {
            Log.e("AudioStreamer", "Error init socket", e)
            return 0
        }
    }

    suspend fun startCall(peerIp: InetAddress, peerPort: Int, scope: CoroutineScope, defaultSpeaker: Boolean = true) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioStreamer", "Recording permission not granted")
            return@withContext
        }

        val bufferSizeIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val bufferSizeOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)

        if (bufferSizeIn == AudioRecord.ERROR_BAD_VALUE || bufferSizeOut == AudioTrack.ERROR_BAD_VALUE) {
            Log.e("AudioStreamer", "Bad buffer size")
            return@withContext
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphoneState(audioManager, defaultSpeaker)
            _isSpeakerphoneOn.value = defaultSpeaker

            proximitySensor?.let {
                sensorManager.registerListener(this@AudioStreamer, it, SensorManager.SENSOR_DELAY_NORMAL)
            }

            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelConfigIn, audioFormat, bufferSizeIn)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build())
                .setBufferSizeInBytes(bufferSizeOut)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord?.startRecording()
            audioTrack?.play()

            recordingJob = scope.launch(Dispatchers.IO) {
                val chunkSize = java.lang.Math.min(bufferSizeIn, 1024)
                val buffer = ByteArray(chunkSize)
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && !isMuted) {
                        try {
                            val packet = DatagramPacket(buffer, read, peerIp, peerPort)
                            udpSocket?.send(packet)
                        } catch (e: Exception) {
                            // Ignore network exceptions during active call loop
                        }
                    }
                }
            }

            playingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)
                        if (packet.length > 0) {
                            audioTrack?.write(packet.data, 0, packet.length)
                        }
                    } catch (e: Exception) {
                        // Ignore network exceptions during active call loop 
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioStreamer", "Call error", e)
            stopCall()
        }
    }

    fun stopCall() {
        recordingJob?.cancel()
        playingJob?.cancel()
        recordingJob = null
        playingJob = null

        sensorManager.unregisterListener(this)

        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null

        try { udpSocket?.close() } catch(e: Exception) {}
        udpSocket = null
        
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            setSpeakerphoneState(audioManager, false)
        } catch (e: Exception) {}

        isMuted = false
        manualSpeakerOn = null
        _isSpeakerphoneOn.value = false
    }

    fun toggleSpeakerphone(on: Boolean, isUserAction: Boolean = false) {
        if (isUserAction) {
            manualSpeakerOn = on
        }
        _isSpeakerphoneOn.value = on
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            setSpeakerphoneState(audioManager, on)
        } catch (e: Exception) {}
    }

    private fun setSpeakerphoneState(audioManager: android.media.AudioManager, on: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (on) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                val devices = audioManager.availableCommunicationDevices
                val earpiece = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange && distance < 5f
            if (isNear) {
                toggleSpeakerphone(false, isUserAction = false)
            } else {
                toggleSpeakerphone(manualSpeakerOn ?: true, isUserAction = false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
