package com.example.ui.chat

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CallState
import com.example.ConnectionState
import com.example.ui.call.CallOverlay
import com.example.ui.call.CallViewModel
import com.example.ui.chat.components.MessageBubble

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    callViewModel: CallViewModel,
    onStartCallRequested: () -> Unit,
    onAcceptCallRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chatState by chatViewModel.uiState.collectAsState()
    val callState by callViewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (callState.callState != CallState.IDLE) {
            CallOverlay(
                uiState = callState,
                onAccept = onAcceptCallRequested,
                onEndCall = {
                    if (callState.callState == CallState.INCOMING) callViewModel.rejectCall()
                    else callViewModel.endCall()
                },
                onToggleSpeaker = { callViewModel.toggleSpeakerphone() }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFEF7FF))
                    .systemBarsPadding()
            ) {
                // Top App Bar / Header
                ChatHeader(
                    connectionState = chatState.connectionState,
                    autoIntercom = chatState.autoIntercomEnabled,
                    onCallClick = onStartCallRequested,
                    onSearchClick = { chatViewModel.setPeersDialogVisible(true) },
                    onToggleAutoIntercom = { chatViewModel.toggleAutoIntercom() },
                    onDisconnect = { chatViewModel.disconnect() }
                )

                // Connected Peer Info Banner
                if (chatState.connectionState == ConnectionState.CONNECTED && chatState.connectedPeer != null) {
                    ConnectedPeerBanner(
                        peerName = chatState.connectedPeer!!.name,
                        onChangeClick = { chatViewModel.disconnect() }
                    )
                }

                // Main Chat & Discovery Area
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (chatState.connectionState == ConnectionState.CONNECTED) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            reverseLayout = true,
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            items(chatState.messages.reversed()) { msg ->
                                MessageBubble(msg)
                            }
                        }
                    } else {
                        DiscoveryPlaceholder(
                            discoveredCount = chatState.discoveredPeers.size,
                            onShowPeers = { chatViewModel.setPeersDialogVisible(true) }
                        )
                    }
                }

                // Bottom Message Input Field
                ChatInputBar(
                    text = chatState.messageText,
                    onTextChanged = { chatViewModel.onMessageTextChanged(it) },
                    onSend = { chatViewModel.sendMessage() },
                    isEnabled = chatState.connectionState == ConnectionState.CONNECTED
                )
            }
        }
    }

    // Peers Discovery Dialog
    if (chatState.showPeersDialog) {
        PeersDialog(
            peers = chatState.discoveredPeers,
            onPeerSelected = { peer -> chatViewModel.connectToPeer(peer) },
            onDismiss = { chatViewModel.setPeersDialogVisible(false) }
        )
    }
}

@Composable
private fun ChatHeader(
    connectionState: ConnectionState,
    autoIntercom: Boolean,
    onCallClick: () -> Unit,
    onSearchClick: () -> Unit,
    onToggleAutoIntercom: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

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
                    text = "Encrypted P2P Node",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = Color(0xFF49454F)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connectionState == ConnectionState.CONNECTED) {
                IconButton(onClick = onCallClick) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Start Call",
                        tint = Color(0xFF1D1B20)
                    )
                }
            }
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search Peers",
                    tint = Color(0xFF1D1B20)
                )
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
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
                                onCheckedChange = { onToggleAutoIntercom() },
                                modifier = Modifier.height(24.dp)
                            )
                        },
                        onClick = onToggleAutoIntercom
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = {
                            showMoreMenu = false
                            onDisconnect()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedPeerBanner(
    peerName: String,
    onChangeClick: () -> Unit
) {
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
                    text = peerName.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = peerName,
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
            onClick = onChangeClick,
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

@Composable
private fun DiscoveryPlaceholder(
    discoveredCount: Int,
    onShowPeers: () -> Unit
) {
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

        if (discoveredCount > 0) {
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onShowPeers,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Text("$discoveredCount peer(s) found. Connect now.")
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    isEnabled: Boolean
) {
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
                value = text,
                onValueChange = onTextChanged,
                textStyle = TextStyle(color = Color(0xFF1D1B20), fontSize = 14.sp),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                cursorBrush = SolidColor(Color(0xFF6750A4)),
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text("Type a local message...", color = Color(0xFF49454F), fontSize = 14.sp)
                    }
                    innerTextField()
                },
                enabled = isEnabled
            )

            IconButton(
                onClick = onSend,
                enabled = isEnabled && text.isNotBlank(),
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled && text.isNotBlank()) Color(0xFF6750A4)
                        else Color(0xFF6750A4).copy(alpha = 0.5f)
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
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

@Composable
private fun PeersDialog(
    peers: List<com.example.Peer>,
    onPeerSelected: (com.example.Peer) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = Color(0xFFFEF7FF),
        textContentColor = Color(0xFF1D1B20),
        titleContentColor = Color(0xFF1D1B20),
        onDismissRequest = onDismiss,
        title = { Text("Available Peers") },
        text = {
            if (peers.isEmpty()) {
                Text("No peers found yet...", color = Color(0xFF49454F))
            } else {
                LazyColumn {
                    items(peers) { peer ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F0F5)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onPeerSelected(peer) }
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
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF6750A4))
            }
        }
    )
}
