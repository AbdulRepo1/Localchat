package com.example

import android.content.Context
import android.util.Log
import com.example.audio.AudioConfig
import com.example.audio.AudioPacketHelper
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.audio.AudioRouter
import com.example.audio.CryptoHelper
import com.example.audio.ProximitySensorHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.SecretKey

/**
 * High-level Audio Call Orchestrator.
 * Coordinates AudioRecorder, AudioPlayer, AudioRouter, UDP sockets, and cryptographic verification.
 */
class AudioStreamer(context: Context) {

    private val audioRouter = AudioRouter(context)
    private val audioRecorder = AudioRecorder(context)
    private val audioPlayer = AudioPlayer()

    val isSpeakerphoneOn: StateFlow<Boolean> = audioRouter.isSpeakerphoneOn
    var isMuted: Boolean = false

    private var udpSocket: DatagramSocket? = null
    private var callScope: CoroutineScope? = null
    private var recordingJob: Job? = null
    private var playingJob: Job? = null

    private var manualSpeakerOn: Boolean? = null

    private val proximityHelper = ProximitySensorHelper(context) { isNear ->
        if (manualSpeakerOn == true) return@ProximitySensorHelper
        if (isNear) {
            audioRouter.setSpeakerphone(false)
        } else {
            audioRouter.setSpeakerphone(manualSpeakerOn ?: true)
        }
    }

    fun initSocket(): Int {
        return try {
            udpSocket = DatagramSocket(0)
            udpSocket?.localPort ?: 0
        } catch (e: Exception) {
            Log.e("AudioStreamer", "Failed to bind UDP socket", e)
            0
        }
    }

    suspend fun startCall(
        peerIp: InetAddress,
        peerPort: Int,
        defaultSpeaker: Boolean = true,
        secretKey: SecretKey? = null
    ) = withContext(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        callScope = scope

        audioRouter.setupCallAudio(defaultSpeaker)
        manualSpeakerOn = null
        proximityHelper.start()

        if (!audioRecorder.start()) {
            stopCall()
            return@withContext
        }

        if (!audioPlayer.start()) {
            stopCall()
            return@withContext
        }

        // Recording & Sending Loop
        recordingJob = scope.launch {
            val rawBuffer = ByteArray(AudioConfig.FRAME_SIZE_BYTES)
            val packetBuffer = ByteArray(AudioConfig.PACKET_BUFFER_SIZE)
            val datagram = DatagramPacket(packetBuffer, packetBuffer.size, peerIp, peerPort)
            var sequenceNumber = 0

            while (isActive) {
                val bytesRead = audioRecorder.read(rawBuffer, 0, rawBuffer.size)
                if (bytesRead > 0 && !isMuted) {
                    val finalPayload: ByteArray
                    val finalPayloadLength: Int

                    if (secretKey != null) {
                        // Encrypt audio PCM with AES-GCM
                        finalPayload = CryptoHelper.encrypt(rawBuffer, bytesRead, secretKey)
                        finalPayloadLength = finalPayload.size
                    } else {
                        finalPayload = rawBuffer
                        finalPayloadLength = bytesRead
                    }

                    val totalPacketSize = AudioPacketHelper.pack(
                        sequenceNumber++,
                        finalPayload,
                        finalPayloadLength,
                        packetBuffer
                    )
                    datagram.length = totalPacketSize
                    try {
                        udpSocket?.send(datagram)
                    } catch (e: Exception) {
                        // Tolerate transient network hiccups
                    }
                }
            }
        }

        // Receiving & Playback Loop
        playingJob = scope.launch {
            val receiveBuffer = ByteArray(AudioConfig.PACKET_BUFFER_SIZE)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            while (isActive) {
                try {
                    receivePacket.length = receiveBuffer.size
                    udpSocket?.receive(receivePacket)

                    // VULN-05 FIX: Strictly validate that packet originates from the designated peer IP and Port!
                    if (receivePacket.address != peerIp || receivePacket.port != peerPort) {
                        Log.w("AudioStreamer", "Dropped rogue UDP packet from ${receivePacket.address}:${receivePacket.port}")
                        continue
                    }

                    if (receivePacket.length > 0) {
                        audioPlayer.writePacket(receiveBuffer, receivePacket.length, secretKey)
                    }
                } catch (e: Exception) {
                    // Tolerate packet drops
                }
            }
        }
    }

    fun stopCall() {
        callScope?.cancel()
        callScope = null
        recordingJob = null
        playingJob = null

        proximityHelper.stop()
        audioRecorder.release()
        audioPlayer.release()
        audioRouter.teardownCallAudio()

        try {
            udpSocket?.close()
        } catch (e: Exception) {}
        udpSocket = null

        isMuted = false
        manualSpeakerOn = null
    }

    fun toggleSpeakerphone(on: Boolean, isUserAction: Boolean = false) {
        if (isUserAction) {
            manualSpeakerOn = on
        }
        audioRouter.setSpeakerphone(on)
    }
}
