package org.deadnet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.deadnet.MainActivity
import org.deadnet.crypto.DeadNetCrypto
import org.deadnet.data.DeadNetDatabase
import org.deadnet.routing.DtnRoutingEngine
import org.deadnet.transport.wifi.WifiDirectTransport
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent Android ForegroundService maintaining the DeadNet mesh network,
 * peer discovery loops, DTN routing engine, sticky notification, and incoming message notifications.
 */
class DeadNetMeshService : Service() {

    companion object {
        const val CHANNEL_ID = "deadnet_mesh_channel"
        const val MESSAGES_CHANNEL_ID = "deadnet_messages_channel"
        const val NOTIFICATION_ID = 1337
        const val ACTION_START = "org.deadnet.action.START"
        const val ACTION_STOP = "org.deadnet.action.STOP"

        private const val PREFS_NAME = "deadnet_prefs"
        private const val PREF_PRIV_KEY = "priv_key_hex"
        private const val PREF_PUB_KEY = "pub_key_hex"

        private val notificationIdCounter = AtomicInteger(2000)

        @Volatile
        var instance: DeadNetMeshService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()

    lateinit var keyPair: DeadNetCrypto.KeyPair
        private set

    lateinit var database: DeadNetDatabase
        private set

    lateinit var routingEngine: DtnRoutingEngine
        private set

    lateinit var wifiTransport: WifiDirectTransport
        private set

    inner class LocalBinder : Binder() {
        fun getService(): DeadNetMeshService = this@DeadNetMeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        // 1. Initialize or load local identity keypair
        keyPair = loadOrCreateKeyPair()

        // 2. Initialize Room Database
        database = DeadNetDatabase.getDatabase(this)

        // 3. Initialize DTN Routing Engine
        routingEngine = DtnRoutingEngine(
            database = database,
            ownKeyPair = keyPair,
            scope = serviceScope
        )

        // Wire incoming message notification
        routingEngine.onIncomingMessage = { senderTitle, messageText, convId ->
            showIncomingMessageNotification(senderTitle, messageText, convId)
        }

        // 4. Initialize Wi-Fi Direct Transport
        wifiTransport = WifiDirectTransport(
            context = this,
            scope = serviceScope
        )
        routingEngine.registerTransport(wifiTransport)

        // 5. Start Mesh Operations
        routingEngine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    fun showIncomingMessageNotification(senderTitle: String, messageText: String, conversationId: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationIdCounter.incrementAndGet(),
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle(senderTitle)
            .setContentText(messageText)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(notificationIdCounter.get(), notification)
    }

    override fun onDestroy() {
        routingEngine.stop()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeadNet Mesh Active")
            .setContentText("Node ID: ${keyPair.nodeId} • Wi-Fi P2P Listening")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "DeadNet Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps DeadNet opportunistic mesh radios and routing active."
                setShowBadge(false)
            }

            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "DeadNet Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when someone sends a mesh message."
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(messagesChannel)
        }
    }

    private fun loadOrCreateKeyPair(): DeadNetCrypto.KeyPair {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privHex = prefs.getString(PREF_PRIV_KEY, null)
        val pubHex = prefs.getString(PREF_PUB_KEY, null)

        if (privHex != null && pubHex != null) {
            try {
                return DeadNetCrypto.KeyPair(
                    publicKey = DeadNetCrypto.hexToBytes(pubHex),
                    privateKey = DeadNetCrypto.hexToBytes(privHex)
                )
            } catch (_: Exception) {}
        }

        val generated = DeadNetCrypto.generateKeyPair()
        prefs.edit()
            .putString(PREF_PRIV_KEY, DeadNetCrypto.run { generated.privateKey.toHexString() })
            .putString(PREF_PUB_KEY, DeadNetCrypto.run { generated.publicKey.toHexString() })
            .apply()
        return generated
    }
}
