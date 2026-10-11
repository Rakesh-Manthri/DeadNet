package org.deadnet.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.deadnet.crypto.DeadNetCrypto
import org.deadnet.crypto.DeadNetCrypto.toHexString
import org.deadnet.data.DeadNetDatabase
import org.deadnet.data.DeadNetMessage
import org.deadnet.protocol.DeadNetPacket
import org.deadnet.service.DeadNetMeshService
import org.deadnet.transport.PeerNode

class DeadNetViewModel(application: Application) : AndroidViewModel(application) {

    private val db = DeadNetDatabase.getDatabase(application)
    private var meshService: DeadNetMeshService? = null

    val messages: StateFlow<List<DeadNetMessage>> = db.deadNetMessageDao()
        .getAllMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _peers = MutableStateFlow<List<PeerNode>>(emptyList())
    val peers: StateFlow<List<PeerNode>> = _peers.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _ownNodeId = MutableStateFlow("UNKNOWN")
    val ownNodeId: StateFlow<String> = _ownNodeId.asStateFlow()

    private val _ownPubKey = MutableStateFlow("")
    val ownPubKey: StateFlow<String> = _ownPubKey.asStateFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? DeadNetMeshService.LocalBinder
            meshService = binder?.getService()
            _isServiceBound.value = true

            meshService?.let { s ->
                _ownNodeId.value = s.keyPair.nodeId
                _ownPubKey.value = DeadNetCrypto.run { s.keyPair.publicKey.toHexString() }

                // Bind peers flow
                viewModelScope.launch {
                    s.wifiTransport.peersFlow.collect { peerList ->
                        _peers.value = peerList
                    }
                }

                // Bind logs flow
                viewModelScope.launch {
                    s.routingEngine.logFlow.collect { logList ->
                        _logs.value = logList
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            _isServiceBound.value = false
        }
    }

    init {
        bindMeshService()
    }

    fun bindMeshService() {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, DeadNetMeshService::class.java)
        app.startService(serviceIntent)
        app.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun sendDirectMessage(recipientPubKeyHex: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val s = meshService
            if (s != null) {
                val recipientKey = try {
                    if (recipientPubKeyHex.length >= 64) {
                        DeadNetCrypto.hexToBytes(recipientPubKeyHex)
                    } else {
                        // Truncated fallback padding for demo/testing
                        val key = ByteArray(32)
                        val bytes = DeadNetCrypto.hexToBytes(recipientPubKeyHex)
                        System.arraycopy(bytes, 0, key, 0, bytes.size.coerceAtMost(32))
                        key
                    }
                } catch (_: Exception) {
                    DeadNetPacket.BROADCAST_KEY
                }

                s.routingEngine.sendDirectMessage(recipientKey, text)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerSos(note: String = "EMERGENCY: Immediate Medical / Evacuation Required") {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            var lat = 0.0
            var lon = 0.0

            try {
                val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val gpsLoc = locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val bestLoc: Location? = gpsLoc ?: netLoc

                if (bestLoc != null) {
                    lat = bestLoc.latitude
                    lon = bestLoc.longitude
                } else {
                    // Nominal backup coordinates (e.g. command center mock)
                    lat = 37.7749
                    lon = -122.4194
                }
            } catch (_: Exception) {
                lat = 37.7749
                lon = -122.4194
            }

            meshService?.routingEngine?.blastSos(lat, lon, note)
        }
    }

    fun connectToPeer(peer: PeerNode) {
        meshService?.wifiTransport?.connectToDevice(peer.address)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {}
    }
}
