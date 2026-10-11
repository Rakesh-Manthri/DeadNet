package org.deadnet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deadnet.data.DeadNetMessage
import org.deadnet.data.DeliveryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    messages: List<DeadNetMessage>,
    onSendMessage: (recipientPubKeyHex: String, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var recipientInput by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F141C))
            .padding(16.dp)
    ) {
        // Target Recipient Header Input
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF192231)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "TARGET RECIPIENT PUBLIC KEY / NODE ID",
                    color = Color(0xFF64B5F6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = recipientInput,
                    onValueChange = { recipientInput = it },
                    placeholder = {
                        Text(
                            text = "Paste 64-hex pubkey or Node ID (empty for broadcast)",
                            color = Color(0xFF78909C),
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF42A5F5),
                        unfocusedBorderColor = Color(0xFF2E3D52)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message Stream
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No mesh messages yet.\nEnter a message below to broadcast or transmit via DTN store-carry-forward.",
                    color = Color(0xFF78909C),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.msgId }) { msg ->
                    MessageBubble(message = msg)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input & Dispatch Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = {
                    Text("Type encrypted mesh packet...", color = Color(0xFF78909C), fontSize = 13.sp)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF2C394B),
                    focusedContainerColor = Color(0xFF161F2E),
                    unfocusedContainerColor = Color(0xFF161F2E)
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(recipientInput, messageText)
                        messageText = ""
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: DeadNetMessage) {
    val isOut = message.isOutgoing
    val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(message.createdAt))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isBroadcast -> Color(0xFF4A121A) // Alert red
                    isOut -> Color(0xFF1E3A5F) // Outgoing blue
                    else -> Color(0xFF1B2433) // Incoming dark slate
                }
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Top header: Sender / Recipient & Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOut) "TO: ${message.recipientNodeId}" else "FROM: ${message.senderNodeId}",
                        color = Color(0xFF90CAF9),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timeFormatted,
                        color = Color(0xFFB0BEC5),
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Message Text (Plaintext if decrypted, or Ciphertext snippet)
                if (message.decryptedText != null) {
                    Text(
                        text = message.decryptedText,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "SEALED CIPHERTEXT (${message.ciphertext.size} bytes)",
                            color = Color(0xFFFFCC80),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Footer Status Pill & DTN Metadata
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // DTN delivery status badge
                    DeliveryStatusBadge(status = message.deliveryStatus, hops = message.hopCount)

                    // Tokens & Hops info
                    Text(
                        text = "Tokens: ${message.tokensRemaining} • Hops: ${message.hopCount}",
                        color = Color(0xFF90A4AE),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusBadge(status: DeliveryStatus, hops: Int) {
    val (label, bg, fg, icon) = when (status) {
        DeliveryStatus.DELIVERED -> Quadruple(
            "DELIVERED (ACK)",
            Color(0xFF1B5E20),
            Color(0xFF81C784),
            Icons.Default.CheckCircle
        )
        DeliveryStatus.CARRIED -> Quadruple(
            "CARRIED (Hops: $hops)",
            Color(0xFFE65100),
            Color(0xFFFFB74D),
            Icons.Default.Sync
        )
        DeliveryStatus.STORED_LOCALLY -> Quadruple(
            "STORED_LOCALLY",
            Color(0xFF263238),
            Color(0xFF90A4AE),
            Icons.Default.HourglassTop
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
