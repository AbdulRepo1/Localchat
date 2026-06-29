package com.example

import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.cancel

data class Peer(
    val name: String,
    val host: InetAddress,
    val port: Int
)

enum class ConnectionState {
    DISCONNECTED, SEARCHING, CONNECTED, DISCONNECTING
}

enum class CallState {
    IDLE, INCOMING, OUTGOING, ACTIVE
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isMine: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class NetworkManager private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val SERVICE_TYPE = "_localchat._tcp."
    private var serviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(4)}".trim()

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _connectionState = MutableStateFlow(ConnectionState.SEARCHING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeer = MutableStateFlow<Peer?>(null)
    val connectedPeer: StateFlow<Peer?> = _connectedPeer.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val prefs = context.getSharedPreferences("LocalChatPrefs", Context.MODE_PRIVATE)
    val autoIntercomEnabled = MutableStateFlow(prefs.getBoolean("autoIntercom", true))

    fun setAutoIntercomEnabled(enabled: Boolean) {
        autoIntercomEnabled.value = enabled
        prefs.edit().putBoolean("autoIntercom", enabled).apply()
    }

    val audioStreamer = AudioStreamer(context)

    init {
        val networkRequest = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                super.onAvailable(network)
                // Wi-Fi reconnected, restart NSD services
                if (_connectionState.value == ConnectionState.DISCONNECTED || _connectionState.value == ConnectionState.SEARCHING) {
                    CoroutineScope(Dispatchers.IO).launch {
                        cleanup()
                        startHosting()
                    }
                }
            }
        })
    }

    private var isDiscovering = false

    fun getLocalIpAddress(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        ips.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {}
        return ips
    }
    
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    fun startHosting() {
        appScope.launch {
            if (serverSocket != null) return@launch
            try {
                serverSocket = ServerSocket(0).apply {
                    reuseAddress = true
                }
                val localPort = serverSocket?.localPort ?: return@launch
                registerService(localPort)
                
                _connectionState.value = ConnectionState.SEARCHING
                startDiscovery()
                
                while (true) {
                    val socket = serverSocket?.accept() ?: break
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        // Already connected to someone, reject new connection
                        socket.close()
                        continue
                    }
                    handleNewConnection(socket, isServer = true)
                }
            } catch (e: Exception) {
                Log.e("NetworkManager", "Server error", e)
                resetConnection()
            }
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@NetworkManager.serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
            }
            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE && service.serviceName != serviceName) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostIp = serviceInfo.host.hostAddress
                            if (!getLocalIpAddress().contains(hostIp)) {
                                val peer = Peer(serviceInfo.serviceName, serviceInfo.host, serviceInfo.port)
                                _discoveredPeers.update { current ->
                                    val filtered = current.filter { it.host.hostAddress != peer.host.hostAddress && it.name != peer.name }
                                    filtered + peer
                                }
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredPeers.update { current ->
                    current.filter { it.name != service.serviceName }
                }
                if (_connectedPeer.value?.name == service.serviceName) {
                    resetConnection()
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    suspend fun connectToPeer(peer: Peer) = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(peer.host, peer.port)
            _connectedPeer.value = peer
            handleNewConnection(socket, isServer = false)
        } catch (e: Exception) {
            Log.e("NetworkManager", "Connection to peer failed", e)
            resetConnection()
        }
    }

    private fun handleNewConnection(socket: Socket, isServer: Boolean) {
        activeSocket = socket
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer = PrintWriter(socket.getOutputStream(), true)

        if (isServer) {
             val remoteIp = socket.inetAddress
             val peerName = _discoveredPeers.value.find { it.host == remoteIp }?.name ?: "Unknown Peer"
             _connectedPeer.value = Peer(peerName, remoteIp, socket.port)
        }

        _connectionState.value = ConnectionState.CONNECTED
        _messages.value = emptyList() // clear history on new connection
        
        stopDiscovery() // Stop discovering while connected

        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    val message = reader?.readLine() ?: break
                    if (message.startsWith("SIGNAL|")) {
                        handleSignal(message.substringAfter("SIGNAL|"))
                    } else {
                        _messages.update { it + ChatMessage(isMine = false, text = message) }
                    }
                }
            } catch (e: SocketException) {
                Log.d("NetworkManager", "Socket disconnected")
            } catch (e: Exception) {
                Log.e("NetworkManager", "Read error", e)
            } finally {
                resetConnection()
            }
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            writer?.println(message)
            _messages.update { it + ChatMessage(isMine = true, text = message) }
        }
    }

    private var targetUdpPort = 0

    private fun handleSignal(signal: String) {
        val parts = signal.split(":")
        val command = parts[0]
        when (command) {
            "CALL_REQ" -> {
                targetUdpPort = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (_callState.value == CallState.IDLE) {
                    if (autoIntercomEnabled.value) {
                        _callState.value = CallState.INCOMING
                        CoroutineScope(Dispatchers.IO).launch { 
                            try {
                                val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                                val r = android.media.RingtoneManager.getRingtone(context, notification)
                                r.play()
                                kotlinx.coroutines.delay(2000)
                                r.stop()
                            } catch(e: Exception) {}

                            if (_callState.value == CallState.INCOMING) {
                                // NEW: Force the app to the foreground to satisfy Android 11+ Microphone Rules
                                try {
                                    val intent = Intent(context, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {}

                                _callState.value = CallState.ACTIVE
                                val port = audioStreamer.initSocket()
                                sendSignaling("CALL_ACK:$port") 
                                val peerInfo = _connectedPeer.value
                                if (peerInfo != null) {
                                    audioStreamer.startCall(peerInfo.host, targetUdpPort, true)
                                }
                            }
                        }
                    } else {
                        _callState.value = CallState.INCOMING
                    }
                } else {
                    CoroutineScope(Dispatchers.IO).launch { sendSignaling("CALL_REJECT") }
                }
            }
            "CALL_ACK" -> {
                targetUdpPort = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (_callState.value == CallState.OUTGOING) {
                    _callState.value = CallState.ACTIVE
                    val peerInfo = _connectedPeer.value
                    if (peerInfo != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            audioStreamer.startCall(peerInfo.host, targetUdpPort)
                        }
                    }
                }
            }
            "CALL_REJECT", "HANGUP" -> {
                _callState.value = CallState.IDLE
                audioStreamer.stopCall()
            }
        }
    }

    private suspend fun sendSignaling(signal: String) = withContext(Dispatchers.IO) {
        writer?.println("SIGNAL|$signal")
    }

    suspend fun sendCallRequest() = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val port = audioStreamer.initSocket()
            sendSignaling("CALL_REQ:$port")
            _callState.value = CallState.OUTGOING
        }
    }

    suspend fun acceptCall() = withContext(Dispatchers.IO) {
        if (_callState.value == CallState.INCOMING) {
            val port = audioStreamer.initSocket()
            sendSignaling("CALL_ACK:$port")
            _callState.value = CallState.ACTIVE
            val peerInfo = _connectedPeer.value
            if (peerInfo != null) {
                audioStreamer.startCall(peerInfo.host, targetUdpPort)
            }
        }
    }

    suspend fun rejectCall() = withContext(Dispatchers.IO) {
        if (_callState.value == CallState.INCOMING) {
            sendSignaling("CALL_REJECT")
            _callState.value = CallState.IDLE
            audioStreamer.stopCall()
        }
    }

    suspend fun endCall() = withContext(Dispatchers.IO) {
        if (_callState.value != CallState.IDLE) {
            sendSignaling("HANGUP")
            _callState.value = CallState.IDLE
            audioStreamer.stopCall()
        }
    }

    fun resetConnection() {
        _connectionState.value = ConnectionState.DISCONNECTING
        _callState.value = CallState.IDLE
        audioStreamer.stopCall()
        try { activeSocket?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { writer?.close() } catch (e: Exception) {}
        
        activeSocket = null
        reader = null
        writer = null
        _connectedPeer.value = null
        _connectionState.value = ConnectionState.SEARCHING
        
        startDiscovery()
    }

    fun cleanup() {
        resetConnection()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        if (registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener) } catch (e: Exception) {}
        }
        stopDiscovery()
    }

    private fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false
        if (discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        }
        _discoveredPeers.value = emptyList()
    }
}
