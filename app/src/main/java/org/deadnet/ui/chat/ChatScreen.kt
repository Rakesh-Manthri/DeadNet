package org.deadnet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.deadnet.data.DeadNetMessage
import org.deadnet.transport.PeerNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConversationSummary(
    val conversationId: String,
    val displayName: String,
    val isOnline: Boolean,
    val lastMessage: DeadNetMessage?
)

@Composable
fun ChatScreen(
    messages: List<DeadNetMessage>,
    knownPeers: List<PeerNode>,
    selectedConversationId: String?,
    onSelectConversation: (String?) -> Unit,
    onSendMessage: (conversationId: String, text: String) -> Unit,
    onDeleteMessage: (msgId: String) -> Unit,
    onDeleteConversation: (conversationId: String) -> Unit,
    onClearAllMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedConversationId == null) {
        ConversationListView(
            messages = messages,
            knownPeers = knownPeers,
            onSelectConversation = onSelectConversation,
            onClearAllMessages = onClearAllMessages,
            modifier = modifier
        )
    } else {
        // Find matching messages including any aliased messages
        val threadMessages = messages.filter { 
            it.conversationId == selectedConversationId || 
            (selectedConversationId != "BROADCAST" && knownPeers.find { p -> p.nodeId == selectedConversationId }?.let { p ->
                (p.address.isNotBlank() && it.conversationId == p.address) || it.conversationTitle.equals(p.name, ignoreCase = true)
            } == true)
        }

        ConversationThreadView(
            conversationId = selectedConversationId,
            messages = threadMessages,
            knownPeers = knownPeers,
            onBack = { onSelectConversation(null) },
            onSendMessage = { text -> onSendMessage(selectedConversationId, text) },
            onDeleteMessage = onDeleteMessage,
            onDeleteConversation = {
                onDeleteConversation(selectedConversationId)
                onSelectConversation(null)
            },
            modifier = modifier
        )
    }
}

/**
 * Screen A: Master Conversation List (Broadcast Channel + Deduplicated Direct Node Chats).
 */
@Composable
fun ConversationListView(
    messages: List<DeadNetMessage>,
    knownPeers: List<PeerNode>,
    onSelectConversation: (String?) -> Unit,
    onClearAllMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var showClearAllDialog by remember { mutableStateOf(false) }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Messages", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete all messages across all conversations?") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllMessages()
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Deduplicate conversations so each physical peer has exactly ONE entry
    val directConversations = remember(knownPeers, messages) {
        val list = mutableListOf<ConversationSummary>()
        val seenKeys = mutableSetOf<String>()

        // 1. Add all known verified peers
        knownPeers.forEach { peer ->
            val convMessages = messages.filter { 
                it.conversationId == peer.nodeId || 
                (peer.address.isNotBlank() && it.conversationId == peer.address) ||
                it.conversationTitle.equals(peer.name, ignoreCase = true)
            }
            val lastMsg = convMessages.maxByOrNull { it.createdAt }
            seenKeys.add(peer.nodeId)
            if (peer.address.isNotBlank()) seenKeys.add(peer.address)

            list.add(
                ConversationSummary(
                    conversationId = peer.nodeId,
                    displayName = peer.name.ifBlank { "Nearby Mesh Peer" },
                    isOnline = peer.isConnected,
                    lastMessage = lastMsg
                )
            )
        }

        // 2. Add any remaining historical conversations
        val remainingConvIds = messages
            .map { it.conversationId }
            .filter { it != "BROADCAST" && !seenKeys.contains(it) }
            .distinct()

        remainingConvIds.forEach { convId ->
            val convMessages = messages.filter { it.conversationId == convId }
            val lastMsg = convMessages.maxByOrNull { it.createdAt }
            val title = lastMsg?.conversationTitle?.takeIf { !it.startsWith("Node ") && it.isNotBlank() }
                ?: "Nearby Mesh Peer"

            list.add(
                ConversationSummary(
                    conversationId = convId,
                    displayName = title,
                    isOnline = false,
                    lastMessage = lastMsg
                )
            )
        }

        list
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "CONVERSATIONS",
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Encrypted Mesh Channels",
                    color = colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { showClearAllDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear All Messages",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Mesh Broadcast Channel Card
            item {
                val broadcastMessages = messages.filter { it.isBroadcast || it.conversationId == "BROADCAST" }
                val lastBroadcast = broadcastMessages.maxByOrNull { it.createdAt }
                BroadcastConversationCard(
                    lastMessage = lastBroadcast,
                    onClick = { onSelectConversation("BROADCAST") }
                )
            }

            // 2. Section Header: Direct Node Chats
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DIRECT CHATS (1-ON-1 ENCRYPTED)",
                        color = colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // 3. Known Discovered Peers (Deduplicated, zero raw IDs)
            if (directConversations.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No Direct Peers Connected Yet",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Nearby devices discovered on Radar will automatically pair and appear here.",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(directConversations, key = { it.conversationId }) { summary ->
                    PeerConversationCard(
                        displayName = summary.displayName,
                        isOnline = summary.isOnline,
                        lastMessage = summary.lastMessage,
                        onClick = { onSelectConversation(summary.conversationId) }
                    )
                }
            }
        }
    }
}

@Composable
fun BroadcastConversationCard(
    lastMessage: DeadNetMessage?,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = "Broadcast",
                    tint = colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mesh Broadcast",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface
                    )
                    if (lastMessage != null) {
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(lastMessage.createdAt)),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = lastMessage?.decryptedText ?: "Public channel — delivered to all mesh devices",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PeerConversationCard(
    displayName: String,
    isOnline: Boolean,
    lastMessage: DeadNetMessage?,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.take(2).uppercase(Locale.ROOT),
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSecondaryContainer,
                        fontSize = 16.sp
                    )
                }

                // Online indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF00E676) else Color(0xFFB0BEC5))
                        .align(Alignment.BottomEnd)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE8F5E9)
                        ) {
                            Text(
                                text = "E2E",
                                color = Color(0xFF2E7D32),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (lastMessage != null) {
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(lastMessage.createdAt)),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = lastMessage?.decryptedText
                        ?: if (lastMessage != null) "🔒 Encrypted message" else "Tap to start encrypted chat",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Screen B: 1-on-1 Conversation Thread View.
 */
@Composable
fun ConversationThreadView(
    conversationId: String,
    messages: List<DeadNetMessage>,
    knownPeers: List<PeerNode>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var messageText by remember { mutableStateOf("") }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<DeadNetMessage?>(null) }
    val listState = rememberLazyListState()

    // Title determination (No IDs, friendly names only)
    val isBroadcast = conversationId == "BROADCAST"
    val peer = knownPeers.find { it.nodeId == conversationId }
    val title = if (isBroadcast) {
        "Mesh Broadcast"
    } else {
        peer?.name?.takeIf { !it.startsWith("Node ") && it.isNotBlank() }
            ?: messages.firstOrNull { it.conversationTitle.isNotBlank() && !it.conversationTitle.startsWith("Node ") }?.conversationTitle
            ?: "Nearby Mesh Peer"
    }
    val subtitle = if (isBroadcast) "Broadcast to All Mesh Nodes" else "End-to-End Encrypted (X25519)"

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            title = { Text("Clear Conversation", fontWeight = FontWeight.Bold) },
            text = { Text("Delete all messages in this conversation? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteConversation()
                        showDeleteChatDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (messageToDelete != null) {
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Delete Message", fontWeight = FontWeight.Bold) },
            text = { Text("Delete this message from local storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        messageToDelete?.msgId?.let { onDeleteMessage(it) }
                        messageToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        // Thread Header
        Surface(
            color = colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(onClick = { showDeleteChatDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Chat",
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Message Thread Content
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isBroadcast) Icons.Default.CellTower else Icons.Default.Lock,
                        contentDescription = null,
                        tint = colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isBroadcast)
                            "Broadcast channel is empty.\nAny message sent here is relayed to all mesh devices."
                        else
                            "No messages with $title yet.\nMessages are end-to-end encrypted and routed opportunistically.",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.msgId }) { msg ->
                    PrivateMessageBubble(
                        message = msg,
                        onDeleteClick = { messageToDelete = msg }
                    )
                }
            }
        }

        // Input Composer Bar
        Surface(
            color = colorScheme.surface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = {
                        Text(
                            text = if (isBroadcast) "Broadcast to mesh..." else "Secure message to $title...",
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface,
                        focusedBorderColor = colorScheme.primary,
                        unfocusedBorderColor = colorScheme.outline,
                        focusedContainerColor = colorScheme.surface,
                        unfocusedContainerColor = colorScheme.surface
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            onSendMessage(messageText)
                            messageText = ""
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    modifier = Modifier.size(48.dp),
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
}

@Composable
fun PrivateMessageBubble(
    message: DeadNetMessage,
    onDeleteClick: () -> Unit
) {
    val isOut = message.isOutgoing
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatted = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(message.createdAt))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isOut) 14.dp else 2.dp,
                bottomEnd = if (isOut) 2.dp else 14.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isOut) colorScheme.primaryContainer else colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Message Payload
                if (message.decryptedText != null) {
                    Text(
                        text = message.decryptedText,
                        color = if (isOut) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFF57C00),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Encrypted Message (In Transit)",
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Footer: Delivery Status & Timestamp (Zero raw IDs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isOut) {
                            Icon(
                                imageVector = if (message.isDelivered) Icons.Default.DoneAll else Icons.Default.Done,
                                contentDescription = null,
                                tint = if (message.isDelivered) Color(0xFF00E676) else colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = timeFormatted,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                        if (message.hopCount > 0) {
                            Text(
                                text = " • ${message.hopCount} hop(s)",
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
