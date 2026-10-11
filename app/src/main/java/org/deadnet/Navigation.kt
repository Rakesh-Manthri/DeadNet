package org.deadnet

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.deadnet.ui.DeadNetViewModel
import org.deadnet.ui.chat.ChatScreen
import org.deadnet.ui.radar.RadarScreen
import org.deadnet.ui.visualizer.VisualizerScreen

enum class DeadNetTab(val title: String) {
    RADAR("Radar View"),
    CHAT("Mesh Chat"),
    VISUALIZER("Mesh Visualizer")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    viewModel: DeadNetViewModel = viewModel()
) {
    var selectedTab by remember { mutableStateOf(DeadNetTab.RADAR) }
    val colorScheme = MaterialTheme.colorScheme

    val ownNodeId by viewModel.ownNodeId.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val knownPeers by viewModel.knownPeers.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val selectedConversationId by viewModel.selectedConversationId.collectAsState()
    val fileTransferSessions by viewModel.fileTransferSessions.collectAsState()

    // Request permissions on startup
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        if (grantedMap.values.any { it }) {
            viewModel.triggerScan()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Intercept back button to prevent unexpected app exit
    BackHandler(enabled = selectedConversationId != null || selectedTab != DeadNetTab.RADAR) {
        if (selectedConversationId != null) {
            // Exit conversation thread back to conversations list
            viewModel.selectConversation(null)
        } else if (selectedTab != DeadNetTab.RADAR) {
            // Return to primary Radar tab
            selectedTab = DeadNetTab.RADAR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DEADNET",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[${selectedTab.title}]",
                            color = colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.surface,
                    titleContentColor = colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = colorScheme.surface,
                contentColor = colorScheme.onSurface
            ) {
                NavigationBarItem(
                    selected = selectedTab == DeadNetTab.RADAR,
                    onClick = { selectedTab = DeadNetTab.RADAR },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = "Radar") },
                    label = { Text("Radar", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        indicatorColor = colorScheme.primary.copy(alpha = 0.15f),
                        unselectedIconColor = colorScheme.onSurfaceVariant,
                        unselectedTextColor = colorScheme.onSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == DeadNetTab.CHAT,
                    onClick = { selectedTab = DeadNetTab.CHAT },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                    label = { Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        indicatorColor = colorScheme.primary.copy(alpha = 0.15f),
                        unselectedIconColor = colorScheme.onSurfaceVariant,
                        unselectedTextColor = colorScheme.onSurfaceVariant
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == DeadNetTab.VISUALIZER,
                    onClick = { selectedTab = DeadNetTab.VISUALIZER },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Visualizer") },
                    label = { Text("Visualizer", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        indicatorColor = colorScheme.primary.copy(alpha = 0.15f),
                        unselectedIconColor = colorScheme.onSurfaceVariant,
                        unselectedTextColor = colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                DeadNetTab.RADAR -> RadarScreen(
                    deviceName = viewModel.myDeviceName,
                    peers = peers,
                    onConnectPeer = { peer -> viewModel.connectToPeer(peer) },
                    onDisconnectPeer = { viewModel.disconnectPeer() },
                    onTriggerScan = { viewModel.triggerScan() },
                    onOpenChat = { peer ->
                        viewModel.selectConversationForPeer(peer)
                        selectedTab = DeadNetTab.CHAT
                    }
                )
                DeadNetTab.CHAT -> ChatScreen(
                    messages = messages,
                    knownPeers = knownPeers,
                    selectedConversationId = selectedConversationId,
                    fileTransferSessions = fileTransferSessions,
                    onSelectConversation = { viewModel.selectConversation(it) },
                    onSendMessage = { convId, text -> viewModel.sendMessageToConversation(convId, text) },
                    onSendFile = { convId, uri -> viewModel.sendFile(uri, convId) },
                    onDeleteMessage = { viewModel.deleteMessage(it) },
                    onDeleteConversation = { viewModel.deleteConversation(it) },
                    onClearAllMessages = { viewModel.clearAllMessages() }
                )
                DeadNetTab.VISUALIZER -> VisualizerScreen(
                    ownNodeId = ownNodeId,
                    peers = peers,
                    messages = messages,
                    logs = logs
                )
            }
        }
    }
}
