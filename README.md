# DeadNet 📡

**DeadNet** is an infrastructure-free, delay-tolerant, decentralized mesh communication Android app designed for post-collapse, emergency disaster recovery, censorship circumvention, and zero-connectivity environments.

It operates with **zero cellular networks, zero internet access, zero SIM cards, and zero central servers**, relaying end-to-end encrypted messages and files directly peer-to-peer using multi-hop store-carry-forward routing.

---

## ⚡ Core Features

- **Decentralized Multi-Radio Mesh**:
  - **Wi-Fi Direct (P2P)**: High-bandwidth direct TCP links for fast message and file transfer.
  - **BLE Ambient Relay (Stage 2)**: Low-power Bluetooth Low Energy GATT server/client for continuous background node discovery and opportunistic packet forwarding.
- **End-to-End Encryption (E2EE)**:
  - Cryptographic identities powered by **X25519 (Curve25519)** key pairs.
  - Symmetric authenticated encryption via **ChaCha20-Poly1305** sealed boxes with ephemeral subkeys derived via **HKDF-SHA256**.
  - No phone numbers, emails, MAC addresses, or raw IDs exposed to users.
- **Delay-Tolerant Network (DTN) Routing**:
  - **Store-Carry-Forward Spray-and-Wait ($L=5$) Routing**: Packets are buffered in local Room SQLite storage and replicated opportunistically whenever peers cross physical proximity.
  - **Reverse ACK Delivery Receipts**: Delivers delivery confirmations back to original sender nodes over the mesh.
  - **Ingress Deduplication & Eviction**: Replay protection via seen-packet caches with a 10-minute queue eviction daemon.
- **Peer-to-Peer File Sharing**:
  - Chunked 16 KB binary transfers over mesh links with pacing and completion tracking.
  - In-app progress bars and direct integration with Android `FileProvider` to tap and open received files.
- **Private 1-on-1 & Mesh Broadcast Channels**:
  - Encrypted 1-on-1 direct conversations.
  - Public mesh-wide broadcast channel relayed across all reachable nodes.
  - Long-press message actions (Resend to Mesh, Copy Text, Share, Delete).
- **Interactive Mesh Visualizer**:
  - Live node radar and network topology graph.
  - DTN protocol simulator and real-time frame event log inspection.
- **Background Foreground Service**:
  - Persistent Android service running continuous discovery, packet listening, and incoming chat notifications.

---

## 🏗️ Architecture

```
DeadNetMeshService (Foreground Service)
├── KeyPair (X25519, persisted in SharedPreferences)
├── DeadNetDatabase (Room SQLite persistence)
│   ├── DeadNetMessageDao (Messages & Conversation threads)
│   └── SeenPacketDao (Ingress deduplication)
├── FileTransferManager (16 KB chunked P2P file engine)
├── DtnRoutingEngine (Store-Carry-Forward Spray-and-Wait)
└── Transport Layer Interfaces
    ├── WifiDirectTransport (TCP Socket Engine / P2P Groups)
    └── BleTransport (GATT Server + BLE Scanner/Client)
```

---

## 📦 Protocol Packet Layout (92-Byte Fixed Binary Header)

All mesh communications use a compact 92-byte binary protocol:

| Offset | Field | Length | Description |
|---|---|---|---|
| 0 | `magic` | 2 bytes | Protocol identifier (`0xDEAD`) |
| 2 | `version` | 1 byte | Protocol version (`0x01`) |
| 3 | `msgType` | 1 byte | `0x01`=Direct, `0x02`=Spray-and-Wait, `0x03`=Reverse ACK, `0x04`=Handshake, `0x05`=File Meta, `0x06`=File Chunk |
| 4 | `msgId` | 16 bytes | Unique packet UUID |
| 20 | `senderPubKey` | 32 bytes | Sender's X25519 public key |
| 52 | `recipientPubKey`| 32 bytes | Recipient public key (all zeros for Broadcast) |
| 84 | `ttl` | 4 bytes | Unix epoch expiry timestamp (seconds) |
| 88 | `hopCount` | 1 byte | Hops traversed |
| 89 | `tokensRemaining`| 1 byte | Token replication quota ($L$) |
| 90 | `payloadLength` | 2 bytes | Byte length of following payload |
| 92 | `payload` | Variable | Encrypted ciphertext or raw chunk data |

---

## 🛠️ Tech Stack & Requirements

- **Platform**: Android 8.0+ (API 26 to API 35+)
- **Language**: Kotlin 2.0+
- **UI Toolkit**: Jetpack Compose + Material 3
- **Local Persistence**: Room SQLite + Flow / StateFlow
- **Cryptography**: BouncyCastle (`X25519Agreement`, `ChaCha20Poly1305`, `HKDFBytesGenerator`)
- **Build System**: Gradle 9.x + Android Gradle Plugin 8.9+

---

## 🚀 Getting Started

### 1. Clone the repository
```bash
git clone https://github.com/Rakesh-Manthri/DeadNet.git
cd DeadNet
```

### 2. Build Debug APK
```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Run Unit Tests
```bash
# Windows
.\gradlew.bat test

# macOS / Linux
./gradlew test
```

### 4. Install on Devices
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 Permissions Used

- `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` for Wi-Fi Direct peer discovery and sockets.
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` for Stage 2 BLE ambient relay.
- `ACCESS_FINE_LOCATION` required by the Android OS for Wi-Fi P2P and Bluetooth scanning.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` for continuous background mesh routing.
- `POST_NOTIFICATIONS` for background incoming message notifications.

---

## 📜 License

This project is licensed under the MIT License - see the LICENSE file for details.
