import SwiftUI

struct CallOverlay: View {
    @ObservedObject var networkManager: NetworkManager
    
    var body: some View {
        VStack {
            Spacer()
            
            Text(statusText)
                .font(.title2)
                .fontWeight(.bold)
                .foregroundColor(.white)
            
            Spacer()
            
            HStack(spacing: 60) {
                if networkManager.callState == .INCOMING && !networkManager.autoIntercomEnabled {
                    Button(action: {
                        networkManager.sendSignaling("CALL_ACK:\(networkManager.audioStreamer.localUdpPort)")
                        networkManager.callState = .ACTIVE
                        networkManager.audioStreamer.startCall(peerIp: networkManager.connectedPeer?.host ?? "127.0.0.1", peerPort: 5005)
                    }) {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 36))
                            .padding(24)
                            .background(Color.green)
                            .foregroundColor(.white)
                            .clipShape(Circle())
                    }
                }
                
                Button(action: {
                    networkManager.sendSignaling("HANGUP")
                    networkManager.callState = .IDLE
                    networkManager.audioStreamer.stopCall()
                }) {
                    Image(systemName: "phone.down.fill")
                        .font(.system(size: 36))
                        .padding(24)
                        .background(Color.red)
                        .foregroundColor(.white)
                        .clipShape(Circle())
                }
            }
            .padding(.bottom, 60)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.9))
        .edgesIgnoringSafeArea(.all)
    }
    
    var statusText: String {
        switch networkManager.callState {
        case .INCOMING: return "Incoming Call..."
        case .OUTGOING: return "Calling..."
        case .ACTIVE: return "Call Active"
        default: return ""
        }
    }
}
