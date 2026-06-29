package com.example

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppState {
    DISCONNECTED, SEARCHING, PEER_CONNECTED, IN_CALL
}

class MainActivity : ComponentActivity() {
    private lateinit var networkManager: NetworkManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkManager = NetworkManager.getInstance(this)
        
        try {
            val serviceIntent = Intent(this, LocalChatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // NEW FIX: Only ask for battery exemption AFTER permissions are handled
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // 1. Request all vital permissions upfront
                    val permissions = mutableListOf(
                        Manifest.permission.INTERNET,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                        Manifest.permission.RECORD_AUDIO // CRITICAL for auto-answer
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                    
                    networkManager.startHosting()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFFEF7FF)
                ) {
                    ChatAppScreen(networkManager)
                }
            }
        }
    }
}

@Composable
fun ChatAppScreen(networkManager: NetworkManager) {
    val connectionState by networkManager.connectionState.collectAsStateWithLifecycle()
    val connectedPeer by networkManager.connectedPeer.collectAsStateWithLifecycle()
    val discoveredPeers by networkManager.discoveredPeers.collectAsStateWithLifecycle()
    val messages by networkManager.messages.collectAsStateWithLifecycle()
    val localIp = remember { networkManager.getLocalIpAddress() }
    
    val scope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }
    var showPeersDialog by remember { mutableStateOf(false) }
    val callState by networkManager.callState.collectAsStateWithLifecycle()
    val autoIntercom by networkManager.autoIntercomEnabled.collectAsStateWithLifecycle()

    val appState = remember(connectionState, callState) {
        if (callState != CallState.IDLE) {
            AppState.IN_CALL
        } else {
            when (connectionState) {
                ConnectionState.CONNECTED -> AppState.PEER_CONNECTED
                ConnectionState.SEARCHING -> AppState.SEARCHING
                else -> AppState.DISCONNECTED
            }
        }
    }

    val context = LocalContext.current
    val isOutgoing = remember { mutableStateOf(false) }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val serviceIntent = Intent(context, LocalChatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            scope.launch {
                if (isOutgoing.value) networkManager.sendCallRequest()
                else networkManager.acceptCall()
            }
        }
    }

    val attemptCall: () -> Unit = {
        isOutgoing.value = true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val serviceIntent = Intent(context, LocalChatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {}
            scope.launch { networkManager.sendCallRequest() }
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val attemptAccept: () -> Unit = {
        isOutgoing.value = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val serviceIntent = Intent(context, LocalChatService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {}
            scope.launch { networkManager.acceptCall() }
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (appState == AppState.IN_CALL) {
            CallOverlay(
                networkManager = networkManager,
                callState = callState,
                peer = connectedPeer,
                onAccept = attemptAccept
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFEF7FF))
                    .systemBarsPadding()
            ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFEF7FF))
                .border(width = 1.dp, color = Color(0xFFCAC4D0).copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "LocalChat",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                    color = Color(0xFF1D1B20)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${Build.MANUFACTURER} ${Build.MODEL}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        color = Color(0xFF49454F)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connectionState == ConnectionState.CONNECTED) {
                    IconButton(onClick = attemptCall) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                }
                IconButton(onClick = { showPeersDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search Peers",
                        tint = Color(0xFF1D1B20)
                    )
                }
                Box {
                    var showMoreMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = Color(0xFF1D1B20)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto-Answer") },
                            trailingIcon = {
                                Switch(
                                    checked = autoIntercom,
                                    onCheckedChange = { networkManager.setAutoIntercomEnabled(it) },
                                    modifier = Modifier.height(24.dp)
                                )
                            },
                            onClick = { networkManager.setAutoIntercomEnabled(!autoIntercom) }
                        )
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            onClick = {
                                showMoreMenu = false
                                networkManager.resetConnection()
                            }
                        )
                    }
                }
            }
        }

        // Connection Banner
        if (connectionState == ConnectionState.CONNECTED && connectedPeer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEADDFF))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6750A4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connectedPeer!!.name.take(2).uppercase(),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = connectedPeer!!.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF21005D)
                        )
                        Text(
                            text = "CONNECTED",
                            fontSize = 10.sp,
                            color = Color(0xFF21005D).copy(alpha = 0.7f),
                            letterSpacing = (-0.2).sp
                        )
                    }
                }
                Surface(
                    onClick = { networkManager.resetConnection() },
                    shape = CircleShape,
                    color = Color.White
                ) {
                    Text(
                        text = "Change",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6750A4)
                    )
                }
            }
        }

        // Chat Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (connectionState == ConnectionState.CONNECTED) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(messages.reversed()) { msg ->
                        MessageBubble(msg)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFF6750A4))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Searching for friends on your network...",
                        color = Color(0xFF49454F),
                        fontSize = 14.sp
                    )
                    
                    if (discoveredPeers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { showPeersDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                        ) {
                            Text("${discoveredPeers.size} peer(s) found. Connect now.")
                        }
                    }
                }
            }
        }

        // Bottom Input Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFEF7FF))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F0F5), CircleShape)
                    .border(1.dp, Color(0xFFCAC4D0), CircleShape)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    textStyle = TextStyle(color = Color(0xFF1D1B20), fontSize = 14.sp),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    cursorBrush = SolidColor(Color(0xFF6750A4)),
                    decorationBox = { innerTextField ->
                        if (messageText.isEmpty()) {
                            Text("Type a local message...", color = Color(0xFF49454F), fontSize = 14.sp)
                        }
                        innerTextField()
                    },
                    enabled = connectionState == ConnectionState.CONNECTED
                )
                
                IconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            scope.launch {
                                networkManager.sendMessage(messageText)
                                messageText = ""
                            }
                        }
                    },
                    enabled = connectionState == ConnectionState.CONNECTED && messageText.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (connectionState == ConnectionState.CONNECTED && messageText.isNotBlank()) Color(0xFF6750A4) else Color(0xFF6750A4).copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            // Status footer
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.height(1.dp).width(32.dp).background(Color(0xFF49454F).copy(alpha = 0.4f)))
                Text(
                    text = "NSD ACTIVE",
                    color = Color(0xFF49454F).copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Box(modifier = Modifier.height(1.dp).width(32.dp).background(Color(0xFF49454F).copy(alpha = 0.4f)))
            }
        }
        }
    }
    }

    if (showPeersDialog) {
        AlertDialog(
            containerColor = Color(0xFFFEF7FF),
            textContentColor = Color(0xFF1D1B20),
            titleContentColor = Color(0xFF1D1B20),
            onDismissRequest = { showPeersDialog = false },
            title = { Text("Available Peers") },
            text = {
                if (discoveredPeers.isEmpty()) {
                    Text("No peers found yet...", color = Color(0xFF49454F))
                } else {
                    LazyColumn {
                        items(discoveredPeers) { peer ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F0F5)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        scope.launch { networkManager.connectToPeer(peer) }
                                        showPeersDialog = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(peer.name, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20))
                                    Text("Ready to connect", fontSize = 12.sp, color = Color(0xFF49454F))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPeersDialog = false }) {
                    Text("Close", color = Color(0xFF6750A4))
                }
            }
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isMine = message.isMine
    val backgroundColor = if (isMine) Color(0xFFD3E3FD) else Color(0xFFF3F0F5)
    val textColor = if (isMine) Color(0xFF041E49) else Color(0xFF1D1B20)
    
    val shape = if (isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(topStart = 0.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val timeString = dateFormat.format(Date(message.timestamp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = alignment
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Surface(
                color = backgroundColor,
                shape = shape,
                shadowElevation = 0.5.dp
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = textColor,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeString,
                fontSize = 10.sp,
                color = Color(0xFF49454F),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun CallOverlay(networkManager: NetworkManager, callState: CallState, peer: Peer?, onAccept: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isMuted by remember { mutableStateOf(false) }

    val gradient = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(Color(0xFF2E0F44), Color(0xFF1D1B20))
    )
    
    Box(modifier = Modifier.fillMaxSize().background(gradient)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peer?.name?.take(2)?.uppercase() ?: "?",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = peer?.name ?: "Unknown",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val statusText = when (callState) {
                    CallState.INCOMING -> "Incoming Call..."
                    CallState.OUTGOING -> "Ringing..."
                    CallState.ACTIVE -> "In Call"
                    else -> ""
                }
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp)
            ) {
                if (callState == CallState.INCOMING) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF4CAF50),
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.width(48.dp))
                }

                if (callState == CallState.ACTIVE) {
                    val isSpeakerOn by networkManager.audioStreamer.isSpeakerphoneOn.collectAsState()
                    
                    FloatingActionButton(
                        onClick = {
                            networkManager.audioStreamer.toggleSpeakerphone(!isSpeakerOn, isUserAction = true)
                        },
                        containerColor = if (isSpeakerOn) Color.White else Color.White.copy(alpha = 0.2f),
                        contentColor = if (isSpeakerOn) Color.Black else Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.Mic, contentDescription = "Speaker", modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                }

                val endAction = {
                    scope.launch {
                        if (callState == CallState.INCOMING) networkManager.rejectCall()
                        else networkManager.endCall()
                    }
                }

                FloatingActionButton(
                    onClick = { endAction() },
                    containerColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call, 
                        contentDescription = "End", 
                        tint = Color.White, 
                        modifier = Modifier.size(32.dp).rotate(135f)
                    )
                }
            }
        }
    }
}
