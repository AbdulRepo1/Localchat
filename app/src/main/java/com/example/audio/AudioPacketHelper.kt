package com.example.audio

/**
 * Utility for packing and unpacking packet headers on UDP audio datagrams.
 * Prevents jitter clicks and out-of-order packet glitching.
 */
object AudioPacketHelper {

    fun pack(sequenceNumber: Int, pcmData: ByteArray, pcmLength: Int, outputBuffer: ByteArray): Int {
        outputBuffer[0] = (sequenceNumber shr 24).toByte()
        outputBuffer[1] = (sequenceNumber shr 16).toByte()
        outputBuffer[2] = (sequenceNumber shr 8).toByte()
        outputBuffer[3] = sequenceNumber.toByte()

        System.arraycopy(pcmData, 0, outputBuffer, AudioConfig.HEADER_SIZE_BYTES, pcmLength)
        return pcmLength + AudioConfig.HEADER_SIZE_BYTES
    }

    fun unpackSequence(packetData: ByteArray): Int {
        if (packetData.size < AudioConfig.HEADER_SIZE_BYTES) return -1
        return ((packetData[0].toInt() and 0xFF) shl 24) or
               ((packetData[1].toInt() and 0xFF) shl 16) or
               ((packetData[2].toInt() and 0xFF) shl 8) or
               (packetData[3].toInt() and 0xFF)
    }
}
