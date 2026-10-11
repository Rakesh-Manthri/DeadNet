package org.deadnet.ui.visualizer

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deadnet.data.DeadNetMessage
import org.deadnet.transport.PeerNode
import kotlin.math.cos
import kotlin.math.sin

enum class VisualizerTab(val title: String) {
    TOPOLOGY("Mesh Topology"),
    DTN_SIMULATOR("DTN Simulator"),
    LOGS("Frame Logs")
}

@Composable
fun VisualizerScreen(
    ownNodeId: String,
    peers: List<PeerNode>,
    messages: List<DeadNetMessage>,
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedTab by remember { mutableStateOf(VisualizerTab.TOPOLOGY) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // Tab selector
        SecondaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = colorScheme.surface,
            contentColor = colorScheme.primary
        ) {
            VisualizerTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            text = tab.title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            VisualizerTab.TOPOLOGY -> TopologyView(
                ownNodeId = ownNodeId,
                peers = peers,
                messages = messages
            )
            VisualizerTab.DTN_SIMULATOR -> DtnSimulatorView()
            VisualizerTab.LOGS -> FrameLogsView(logs = logs)
        }
    }
}

/**
 * 1. Live Mesh Topology View
 */
@Composable
fun TopologyView(
    ownNodeId: String,
    peers: List<PeerNode>,
    messages: List<DeadNetMessage>
) {
    val colorScheme = MaterialTheme.colorScheme
    val pulseTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 20f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseRadius"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Metrics Summary Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "PEERS",
                value = "${peers.size}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "STORED DTN",
                value = "${messages.size}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "TOKENS",
                value = "${messages.sumOf { it.tokensRemaining }}",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Interactive Canvas Rendering
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Draw concentric radar range rings
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = 0.08f),
                        radius = 80.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = 0.05f),
                        radius = 130.dp.toPx(),
                        center = center
                    )

                    // Animated ping wave
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = (1f - (pulseRadius / 180f)).coerceIn(0f, 0.3f)),
                        radius = pulseRadius.dp.toPx(),
                        center = center
                    )

                    // Draw connecting lines to peers
                    val orbitRadius = 110.dp.toPx()
                    val peerCount = peers.size.coerceAtLeast(1)
                    val angleStep = (2 * Math.PI) / peerCount

                    peers.forEachIndexed { index, peer ->
                        val angle = index * angleStep - (Math.PI / 2)
                        val peerX = center.x + (orbitRadius * cos(angle)).toFloat()
                        val peerY = center.y + (orbitRadius * sin(angle)).toFloat()
                        val peerOffset = Offset(peerX, peerY)

                        // Link line
                        drawLine(
                            color = if (peer.isConnected) Color(0xFF00E676) else Color(0xFFFFB300),
                            start = center,
                            end = peerOffset,
                            strokeWidth = if (peer.isConnected) 3f else 1.5f,
                            pathEffect = if (!peer.isConnected) PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) else null,
                            cap = StrokeCap.Round
                        )

                        // Peer node circle
                        drawCircle(
                            color = if (peer.isConnected) Color(0xFF00E676) else Color(0xFFFFB300),
                            radius = 14.dp.toPx(),
                            center = peerOffset
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6.dp.toPx(),
                            center = peerOffset
                        )
                    }

                    // Local node center circle
                    drawCircle(
                        color = Color(0xFF1976D2),
                        radius = 20.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = center
                    )
                }

                // Legend / Help overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colorScheme.surfaceVariant.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "This Device • Connected: ${peers.count { it.isConnected }} • Discovered: ${peers.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = colorScheme.onSurface
            )
        }
    }
}

/**
 * 2. DTN Store-Carry-Forward Spray-and-Wait Interactive Simulator
 */
@Composable
fun DtnSimulatorView() {
    val colorScheme = MaterialTheme.colorScheme
    var currentStep by remember { mutableIntStateOf(0) }

    // Simulation steps:
    // 0: Initial state - Message created at Source with L = 5
    // 1: Source meets Relay 1 (Alpha) -> S splits: S keeps 3, R1 gets 2
    // 2: Source meets Relay 2 (Beta)  -> S splits: S keeps 2, R2 gets 1 (Wait Phase for R2)
    // 3: Relay 1 meets Target Node    -> Direct Delivery! Target decrypts & verifies
    // 4: Target emits Reverse ACK     -> Reverses along mesh to mark original complete!

    val stepTitles = listOf(
        "Step 1: Originator generates message with Initial Quota L = 5",
        "Step 2: Originator meets Relay Alpha -> Splits tokens (Alpha gets 2, Source keeps 3)",
        "Step 3: Originator meets Relay Beta -> Splits tokens (Beta gets 1, Source keeps 2)",
        "Step 4: Relay Alpha encounters Destination -> Direct Delivery (Tokens exhausted)",
        "Step 5: Destination generates Reverse ACK -> Confirmed Delivery back to Source"
    )

    val stepDescriptions = listOf(
        "In Spray-and-Wait, message source starts with L quota tokens. Because L=5 > 1, the node is in the SPRAY PHASE and actively distributes copies to intermediate mobile peers.",
        "Relay Alpha (moving physical node) is discovered over Wi-Fi Direct. Source splits its tokens: gives floor(5/2) = 2 tokens to Alpha, and retains ceil(5/2) = 3 tokens locally.",
        "Source subsequently encounters Relay Beta. Tokens are split again: Beta receives floor(3/2) = 1 token, Source retains 2 tokens. Because Beta has L = 1, Beta enters the WAIT PHASE.",
        "Relay Alpha physically moves into radio proximity with the Destination Node. Since the destination matches the recipient public key, Alpha delivers the payload directly regardless of remaining token quota.",
        "The Destination verifies the ChaCha20-Poly1305 MAC, decrypts the payload, and transmits a Reverse ACK (Type 0x03). Carried replicas in transit are garbage collected."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Simulation Controls Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DTN SPRAY-AND-WAIT SANDBOX",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Step ${currentStep + 1} of 5",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )
            }

            Row {
                OutlinedButton(
                    onClick = { currentStep = 0 },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { currentStep = (currentStep + 1) % 5 },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
                ) {
                    Text(if (currentStep == 4) "Restart" else "Next Step", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step explanation card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stepTitles[currentStep],
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stepDescriptions[currentStep],
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Node Visual Cards in the Mesh
        Text(
            text = "SIMULATED NODE CLUSTER",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.primary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Node S (Source)
            item {
                val tokens = when (currentStep) {
                    0 -> 5
                    1 -> 3
                    2 -> 2
                    3 -> 2
                    else -> 0
                }
                val state = when (currentStep) {
                    0 -> "SPRAYING (L=5)"
                    1 -> "SPRAYING (L=3)"
                    2 -> "SPRAYING (L=2)"
                    3 -> "AWAITING ACK"
                    else -> "DELIVERED (ACK RECV)"
                }
                SimulatedNodeCard(
                    nodeName = "Node Source (You)",
                    role = "Originator",
                    tokens = tokens,
                    state = state,
                    isActive = true,
                    isDelivered = currentStep == 4
                )
            }

            // Node Alpha (Relay 1)
            item {
                val tokens = when (currentStep) {
                    0 -> 0
                    1 -> 2
                    2 -> 2
                    3 -> 0
                    else -> 0
                }
                val state = when (currentStep) {
                    0 -> "IDLE (In Range)"
                    1 -> "CARRIED (L=2, Spraying)"
                    2 -> "PHYSICAL TRANSIT"
                    3 -> "DIRECT HANDOFF"
                    else -> "CLEARED BY ACK"
                }
                SimulatedNodeCard(
                    nodeName = "Relay Node Alpha",
                    role = "Mobile Carrier",
                    tokens = tokens,
                    state = state,
                    isActive = currentStep >= 1,
                    isDelivered = currentStep >= 3
                )
            }

            // Node Beta (Relay 2)
            item {
                val tokens = when (currentStep) {
                    0, 1 -> 0
                    2, 3, 4 -> 1
                    else -> 0
                }
                val state = when (currentStep) {
                    0, 1 -> "UNDISCOVERED"
                    2 -> "WAIT PHASE (L=1)"
                    3 -> "WAIT PHASE (L=1)"
                    else -> "CLEARED BY ACK"
                }
                SimulatedNodeCard(
                    nodeName = "Relay Node Beta",
                    role = "Passive Carrier",
                    tokens = tokens,
                    state = state,
                    isActive = currentStep >= 2,
                    isDelivered = false
                )
            }

            // Destination Node
            item {
                val state = when (currentStep) {
                    0, 1, 2 -> "OUT OF RANGE"
                    3 -> "RECEIVED & DECRYPTED"
                    else -> "ACK DISPATCHED (0x03)"
                }
                SimulatedNodeCard(
                    nodeName = "Destination Node [Bob]",
                    role = "Target Recipient",
                    tokens = 0,
                    state = state,
                    isActive = currentStep >= 3,
                    isDelivered = currentStep >= 3
                )
            }
        }
    }
}

@Composable
fun SimulatedNodeCard(
    nodeName: String,
    role: String,
    tokens: Int,
    state: String,
    isActive: Boolean,
    isDelivered: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) colorScheme.surface else colorScheme.surface.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        border = if (isDelivered) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E676)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isDelivered) Color(0xFFE8F5E9) else colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isDelivered) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = nodeName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Tokens: $tokens",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (tokens > 0) colorScheme.primary else colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = role,
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = state,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isDelivered) Color(0xFF2E7D32) else colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 3. Physical Frame Logs View
 */
@Composable
fun FrameLogsView(logs: List<String>) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PHYSICAL FRAME TERMINAL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = "${logs.size} EVENTS",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Awaiting mesh radio traffic...\nFrames transmitted across Wi-Fi Direct and BLE will stream here.",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { log ->
                        val isHandshake = log.contains("Handshake", ignoreCase = true)
                        val isSocket = log.contains("Socket", ignoreCase = true)
                        val isSim = log.contains("[SIMULATOR]", ignoreCase = true)

                        Text(
                            text = log,
                            color = when {
                                isSim -> Color(0xFFFFB300)
                                isHandshake -> Color(0xFF58A6FF)
                                isSocket -> Color(0xFF7EE787)
                                else -> Color(0xFFC9D1D9)
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
