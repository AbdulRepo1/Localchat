import Foundation
import AVFoundation
import Network

class AudioStreamer: ObservableObject {
    private let engine = AVAudioEngine()
    private var inputNode: AVAudioInputNode!
    private var outputNode: AVAudioOutputNode!
    private var playerNode = AVAudioPlayerNode()
    
    private var udpConnection: NWConnection?
    private var isStreaming = false
    
    @Published var isMuted = false
    var localUdpPort: Int = 5005 // Default local UDP port
    
    private let audioFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                            sampleRate: 16000.0,
                                            channels: 1,
                                            interleaved: false)!
    
    func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            // .playAndRecord enables I/O. .voiceChat tunes it for communication.
            // .defaultToSpeaker forces routing to the loudspeaker natively without sensors overriding it.
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch {
            print("Failed to configure AVAudioSession: \(error)")
        }
    }
    
    func startCall(peerIp: String, peerPort: Int) {
        configureAudioSession()
        
        inputNode = engine.inputNode
        outputNode = engine.outputNode
        
        engine.attach(playerNode)
        engine.connect(playerNode, to: outputNode, format: audioFormat)
        
        let host = NWEndpoint.Host(peerIp)
        let port = NWEndpoint.Port(integerLiteral: UInt16(peerPort))
        
        udpConnection = NWConnection(host: host, port: port, using: .udp)
        udpConnection?.start(queue: .global(qos: .userInteractive))
        
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: audioFormat) { [weak self] (buffer, time) in
            guard let self = self, !self.isMuted else { return }
            let audioData = self.bufferToData(buffer: buffer)
            self.udpConnection?.send(content: audioData, completion: .contentProcessed({ error in
                if let error = error {
                    print("Error sending audio data: \(error)")
                }
            }))
        }
        
        do {
            engine.prepare()
            try engine.start()
            playerNode.play()
            isStreaming = true
            receiveAudio()
        } catch {
            print("Error starting audio engine: \(error)")
        }
    }
    
    private func receiveAudio() {
        guard isStreaming else { return }
        udpConnection?.receiveMessage { [weak self] (data, context, isComplete, error) in
            guard let self = self else { return }
            if let data = data {
                // To keep it simple in this conceptual migration, we convert Data back to AVAudioPCMBuffer
                if let buffer = self.dataToBuffer(data: data, format: self.audioFormat) {
                    self.playerNode.scheduleBuffer(buffer, completionHandler: nil)
                }
            }
            if error == nil && self.isStreaming {
                self.receiveAudio()
            }
        }
    }
    
    func stopCall() {
        isStreaming = false
        engine.stop()
        playerNode.stop()
        inputNode?.removeTap(onBus: 0)
        
        udpConnection?.cancel()
        udpConnection = nil
        
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setActive(false)
        } catch {
            print("Failed to deactivate AVAudioSession: \(error)")
        }
    }
    
    private func bufferToData(buffer: AVAudioPCMBuffer) -> Data {
        let frames = buffer.frameLength
        let audioBuffer = buffer.int16ChannelData![0]
        return Data(bytes: audioBuffer, count: Int(frames) * MemoryLayout<Int16>.size)
    }
    
    private func dataToBuffer(data: Data, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frameCount = UInt32(data.count / MemoryLayout<Int16>.size)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return nil }
        buffer.frameLength = frameCount
        
        data.withUnsafeBytes { rawBufferPointer in
            if let pointer = rawBufferPointer.bindMemory(to: Int16.self).baseAddress {
                buffer.int16ChannelData?[0].update(from: pointer, count: Int(frameCount))
            }
        }
        return buffer
    }
}
