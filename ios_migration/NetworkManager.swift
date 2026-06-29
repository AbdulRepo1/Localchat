import Foundation
import Network
import Combine
import UIKit

class NetworkManager: ObservableObject {
    @Published var connectionState: ConnectionState = .DISCONNECTED
    @Published var callState: CallState = .IDLE
    @Published var discoveredPeers: [Peer] = []
    @Published var connectedPeer: Peer?
    @Published var messages: [ChatMessage] = []
    @Published var autoIntercomEnabled: Bool = true
    
    private var browser: NWBrowser?
    private var listener: NWListener?
    private var connection: NWConnection?
    
    private let serviceType = "_localchat._tcp"
    private let serviceDomain = "local."
    private let myName = "\(UIDevice.current.name)-\(UUID().uuidString.prefix(4))"
    
    var audioStreamer = AudioStreamer()
    private var targetUdpPort: Int = 0
    
    func startHosting() {
        connectionState = .SEARCHING
        startListener()
        startBrowsing()
    }
    
    private func startListener() {
        do {
            listener = try NWListener(using: .tcp)
            listener?.service = NWListener.Service(name: myName, type: serviceType, domain: serviceDomain)
            
            listener?.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    print("Listener ready on port \(self?.listener?.port?.rawValue ?? 0)")
                case .failed(let error):
                    print("Listener failed: \(error)")
                    self?.connectionState = .DISCONNECTED
                default:
                    break
                }
            }
            
            listener?.newConnectionHandler = { [weak self] newConnection in
                self?.handleNewConnection(newConnection)
            }
            
            listener?.start(queue: .main)
        } catch {
            print("Failed to start listener: \(error)")
        }
    }
    
    private func startBrowsing() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = true
        
        browser = NWBrowser(for: .bonjour(type: serviceType, domain: serviceDomain), using: parameters)
        
        browser?.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("Browser ready")
            case .failed(let error):
                print("Browser failed: \(error)")
            default:
                break
            }
        }
        
        browser?.browseResultsChangedHandler = { [weak self] results, changes in
            guard let self = self else { return }
            var peers: [Peer] = []
            for result in results {
                if case let NWEndpoint.service(name, _, _, _) = result.endpoint {
                    if name != self.myName {
                        // In a real app, resolve the endpoint. For simplicity, mock the IP/Port.
                        peers.append(Peer(id: UUID(), name: name, host: "0.0.0.0", port: 0))
                    }
                }
            }
            DispatchQueue.main.async {
                self.discoveredPeers = peers
            }
        }
        
        browser?.start(queue: .main)
    }
    
    func connectToPeer(_ peer: Peer) {
        // Here you would resolve the Bonjour service and start a TCP connection
        print("Connecting to \(peer.name)...")
    }
    
    private func handleNewConnection(_ newConnection: NWConnection) {
        connection = newConnection
        connection?.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .ready:
                    self?.connectionState = .CONNECTED
                    self?.receiveMessage()
                case .failed, .cancelled:
                    self?.connectionState = .DISCONNECTED
                    self?.connectedPeer = nil
                default:
                    break
                }
            }
        }
        connection?.start(queue: .main)
    }
    
    private func receiveMessage() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, context, isComplete, error in
            guard let self = self else { return }
            if let data = data, let message = String(data: data, encoding: .utf8) {
                DispatchQueue.main.async {
                    self.processIncomingMessage(message)
                }
            }
            if error == nil && !isComplete {
                self.receiveMessage()
            }
        }
    }
    
    private func processIncomingMessage(_ message: String) {
        let cleanMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanMessage.starts(with: "SIGNAL|") {
            let parts = cleanMessage.components(separatedBy: "|")
            guard parts.count >= 2 else { return }
            let command = parts[1]
            let args = command.components(separatedBy: ":")
            
            switch args[0] {
            case "CALL_REQ":
                if let portStr = args[safe: 1], let port = Int(portStr) {
                    targetUdpPort = port
                    if callState == .IDLE {
                        if autoIntercomEnabled {
                            callState = .INCOMING
                            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                if self.callState == .INCOMING {
                                    self.callState = .ACTIVE
                                    self.audioStreamer.startCall(peerIp: self.connectedPeer?.host ?? "127.0.0.1", peerPort: self.targetUdpPort)
                                    self.sendSignaling("CALL_ACK:\(self.audioStreamer.localUdpPort)")
                                }
                            }
                        } else {
                            callState = .INCOMING
                        }
                    } else {
                        sendSignaling("CALL_REJECT")
                    }
                }
            case "CALL_ACK":
                if let portStr = args[safe: 1], let port = Int(portStr) {
                    targetUdpPort = port
                    callState = .ACTIVE
                    audioStreamer.startCall(peerIp: connectedPeer?.host ?? "127.0.0.1", peerPort: targetUdpPort)
                }
            case "HANGUP":
                callState = .IDLE
                audioStreamer.stopCall()
            default:
                break
            }
        } else {
            let chatMsg = ChatMessage(id: UUID(), senderName: connectedPeer?.name ?? "Unknown", content: cleanMessage, timestamp: Date(), isFromMe: false)
            messages.append(chatMsg)
        }
    }
    
    func sendSignaling(_ signal: String) {
        let msg = "SIGNAL|\(signal)\n"
        sendMessage(msg)
    }
    
    func sendMessage(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        connection?.send(content: data, completion: .contentProcessed({ error in
            if let error = error {
                print("Send error: \(error)")
            }
        }))
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        return indices.contains(index) ? self[index] : nil
    }
}
