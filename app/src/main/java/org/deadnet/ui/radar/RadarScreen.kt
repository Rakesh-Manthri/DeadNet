package org.deadnet.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deadnet.transport.PeerNode
import org.deadnet.transport.TransportType

@Composable
fun RadarScreen(
    deviceName: String,
    peers: List<PeerNode>,
    onConnectPeer: (PeerNode) -> Unit,
    onDisconnectPeer: () -> Unit,
    onTriggerScan: () -> Unit,
    onOpenChat: ((PeerNode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp)
    ) {
        // Node Status Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "THIS DEVICE",
                        color = colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = deviceName,
                        color = colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Radar Scanner Graphic
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colorScheme.surfaceVariant)
                .border(1.dp, colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            RadarPulseEffect(primaryColor = colorScheme.primary)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${peers.size} PHYSICAL PEER(S) DISCOVERED",
                    color = colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Wi-Fi Direct P2P Channel Active",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Discovered Peers Title & Scan Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "NEARBY PEERS",
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onTriggerScan,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "SCAN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No DeadNet peers found nearby.\nEnsure Wi-Fi is enabled on both devices.",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onTriggerScan,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Search for Peers")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(peers, key = { it.address.ifBlank { it.id } }) { peer ->
                    PeerItemCard(
                        peer = peer,
                        onConnect = { onConnectPeer(peer) },
                        onDisconnect = onDisconnectPeer,
                        onOpenChat = onOpenChat?.let { { it(peer) } }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerItemCard(
    peer: PeerNode,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenChat: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transport Type Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (peer.transportType) {
                        TransportType.WIFI_DIRECT -> colorScheme.primary
                        TransportType.BLE -> Color(0xFF00695C)
                        TransportType.ULTRASONIC -> Color(0xFF6A1B9A)
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (peer.transportType) {
                                TransportType.WIFI_DIRECT -> Icons.Default.Wifi
                                TransportType.BLE -> Icons.Default.CellTower
                                TransportType.ULTRASONIC -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = peer.name,
                        color = colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            peer.isConnected -> "CONNECTED • READY"
                            peer.statusText == "INVITED" -> "PAIRING IN PROGRESS..."
                            else -> "AVAILABLE NEARBY"
                        },
                        color = if (peer.isConnected) Color(0xFF2E7D32) else colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Wi-Fi Direct • Signal: ${peer.rssi} dBm",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            if (peer.isConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenChat != null) {
                        Button(
                            onClick = onOpenChat,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(text = "CHAT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(text = "DISCONNECT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (peer.statusText == "INVITED") "PAIRING..." else "CONNECT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun RadarPulseEffect(primaryColor: Color) {
    val transition = rememberInfiniteTransition(label = "RadarPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha"
    )
    val radiusRatio by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Radius"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val maxRadius = size.minDimension / 1.8f
        drawCircle(
            color = primaryColor.copy(alpha = alpha),
            radius = maxRadius * radiusRatio,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
