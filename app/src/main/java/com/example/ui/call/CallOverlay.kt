package com.example.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CallState

@Composable
fun CallOverlay(
    uiState: CallUiState,
    onAccept: () -> Unit,
    onEndCall: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF2E0F44), Color(0xFF1D1B20))
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Caller Info Section
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
                        text = uiState.connectedPeer?.name?.take(2)?.uppercase() ?: "?",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = uiState.connectedPeer?.name ?: "Unknown Peer",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val statusText = when (uiState.callState) {
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

            // Call Action Buttons
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 64.dp)
            ) {
                // Incoming Call: Accept Button
                if (uiState.callState == CallState.INCOMING) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF4CAF50),
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Accept Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Active Call: Speakerphone Toggle
                if (uiState.callState == CallState.ACTIVE) {
                    FloatingActionButton(
                        onClick = onToggleSpeaker,
                        containerColor = if (uiState.isSpeakerphoneOn) Color.White else Color.White.copy(alpha = 0.2f),
                        contentColor = if (uiState.isSpeakerphoneOn) Color.Black else Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.Mic,
                            contentDescription = "Speakerphone",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                }

                // End / Reject Call Button
                FloatingActionButton(
                    onClick = onEndCall,
                    containerColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(135f)
                    )
                }
            }
        }
    }
}
