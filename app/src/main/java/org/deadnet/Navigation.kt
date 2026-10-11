package org.deadnet

import android.Manifest
import android.os.Build
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
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
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
import org.deadnet.ui.sos.SosScreen

enum class DeadNetTab(val title: String) {
    RADAR("Radar View"),
    CHAT("Mesh Chat"),
    SOS("Emergency SOS")
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
    val messages by viewModel.messages.collectAsState()
    val logs by viewModel.logs.collectAsState()

    // Request permissions on startup
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        // When permissions granted, immediately trigger peer scan
        if (grantedMap.values.any { it }) {
            viewModel.triggerScan()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
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
                    selected = selectedTab == DeadNetTab.SOS,
                    onClick = { selectedTab = DeadNetTab.SOS },
                    icon = { Icon(Icons.Default.Warning, contentDescription = "SOS") },
                    label = { Text("SOS", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFD32F2F),
                        selectedTextColor = Color(0xFFD32F2F),
                        indicatorColor = Color(0xFFFFEBEE),
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
                    ownNodeId = ownNodeId,
                    peers = peers,
                    onConnectPeer = { peer -> viewModel.connectToPeer(peer) },
                    onDisconnectPeer = { viewModel.disconnectPeer() },
                    onTriggerScan = { viewModel.triggerScan() }
                )
                DeadNetTab.CHAT -> ChatScreen(
                    messages = messages,
                    onSendMessage = { recipient, text -> viewModel.sendDirectMessage(recipient, text) }
                )
                DeadNetTab.SOS -> SosScreen(
                    logs = logs,
                    onTriggerSos = { viewModel.triggerSos() }
                )
            }
        }
    }
}
