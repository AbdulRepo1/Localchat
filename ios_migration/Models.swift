import Foundation

enum CallState {
    case IDLE
    case INCOMING
    case OUTGOING
    case ACTIVE
}

enum ConnectionState {
    case SEARCHING
    case DISCONNECTED
    case CONNECTED
}

struct ChatMessage: Identifiable, Codable {
    let id: UUID
    let senderName: String
    let content: String
    let timestamp: Date
    let isFromMe: Bool
}

struct Peer: Identifiable, Equatable {
    let id: UUID
    let name: String
    let host: String
    let port: Int
    
    static func ==(lhs: Peer, rhs: Peer) -> Bool {
        return lhs.id == rhs.id
    }
}
