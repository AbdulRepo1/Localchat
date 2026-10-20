package com.example.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier
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
