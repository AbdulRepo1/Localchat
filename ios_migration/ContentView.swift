import SwiftUI

struct ContentView: View {
    @StateObject private var networkManager = NetworkManager()
    
    var body: some View {
        ZStack {
            VStack {
                Text("LocalChat iOS")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .padding()
                
                if networkManager.connectionState == .CONNECTED {
                    Text("Connected to \(networkManager.connectedPeer?.name ?? "Peer")")
                        .foregroundColor(.green)
                        .padding(.bottom, 20)
                    
                    Button(action: {
                        networkManager.callState = .OUTGOING
                        networkManager.sendSignaling("CALL_REQ:\(networkManager.audioStreamer.localUdpPort)")
                    }) {
                        Text("Initiate Intercom Call")
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal)
                    
                    Button(action: {
                        networkManager.sendSignaling("HANGUP")
                        networkManager.callState = .IDLE
                        networkManager.audioStreamer.stopCall()
                    }) {
                        Text("Hangup")
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.red)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal)
                    .padding(.top, 10)
                    
                } else {
                    Text(networkManager.connectionState == .SEARCHING ? "Searching for peers..." : "Disconnected")
                        .foregroundColor(.gray)
                    
                    List(networkManager.discoveredPeers) { peer in
                        Button(action: {
                            networkManager.connectToPeer(peer)
                        }) {
                            HStack {
                                Text(peer.name)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                }
                
                Spacer()
                
                Toggle("Auto-Answer (Intercom)", isOn: $networkManager.autoIntercomEnabled)
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(12)
                    .padding()
            }
            
            if networkManager.callState != .IDLE {
                CallOverlay(networkManager: networkManager)
            }
        }
        .onAppear {
            networkManager.startHosting()
        }
    }
}
