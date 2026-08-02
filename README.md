# BharatChat

**Native, BLE‑only, multi‑hop mesh messaging.**  
No internet, no servers – just you and the people in your mesh.

---

## 🎯 Features

### Messaging
- ✅ **1‑to‑1 encrypted messaging** – ECDH + AES‑256‑GCM with forward secrecy.
- ✅ **Delivery & read receipts** – two‑tick status (sent, delivered, read) with orange indicators.
- ✅ **Voice messages** – record (max 30s), send, and play back (both sender and receiver can play).
- ✅ **Multi‑select message deletion** – long‑press to select multiple messages, delete all at once.
- ✅ **Delete contact** – remove contact and wipe entire chat history.

### Mesh Networking
- ✅ **BLE GATT Server + Client** – each device is both a peripheral and a central, running simultaneously.
- ✅ **B.A.T.M.A.N.‑style routing** – smart routing table with link quality tracking, expiry, and periodic HELLO messages (reduces flooding).
- ✅ **Multi‑hop relaying** – packets are forwarded up to 7 hops through intermediate devices **without** requiring them to be contacts.
- ✅ **Fragment reassembly** – large payloads (voice messages) are split, sent, and reassembled reliably.
- ✅ **Deduplication** – `SeenPacketCache` prevents infinite loops.

### User Interface
- ✅ **Dark theme** – deep gray background with vibrant orange accents.
- ✅ **Pairing via QR code** – scan or paste a 66‑hex compressed public key.
- ✅ **6‑digit short code** – for already‑paired contacts (quick re‑pairing).
- ✅ **Contact management** – rename contacts, delete contacts, pair new contacts.
- ✅ **Smart notifications** – incoming messages trigger a notification **with sound** only if you are **not** already in that chat.
- ✅ **Voice recording UI** – modal with timer, large toggle button (🎤 / ⏹), and cancel option.

### Background & Reliability
- ✅ **Foreground service** – keeps the mesh alive in the background with a persistent notification.
- ✅ **Automatic reconnect** – scanning and advertising restart when Bluetooth turns back on.
- ✅ **Persistent message storage** – messages are saved to Room database even when the app is in the background.

---

## 🔐 Privacy Model

| Layer | Behaviour |
| :--- | :--- |
| **Transport (who can relay)** | **Open** – any BharatChat device forwards packets, regardless of contact status. |
| **Content (what relays see)** | **Sealed** – ECDH + AES‑256‑GCM. Relays only see opaque encrypted bytes. |
| **Messaging (who you can chat with)** | **Gated** – you can only open a chat with someone you've paired via QR code. |
| **Background relay** | **Visible** – foreground service with a persistent notification (required by Android). |

---

## 📂 Architecture

| Component | Description |
| :--- | :--- |
| `BleMeshManager.kt` | Core mesh logic: B.A.T.M.A.N. routing, HELLO messages, relay, ACKs. |
| `BleGattServerManager.kt` | GATT server: advertising, accepting connections, handling writes/notifications. |
| `BleGattClientManager.kt` | GATT client: scanning, connecting, MTU negotiation, notifications. |
| `BlePacket.kt` | Binary packet format with sender/recipient IDs, TTL, and fragment support. |
| `SeenPacketCache.kt` | Bounded, time‑expiring dedup cache for loop prevention. |
| `MeshForegroundService.kt` | Background service with persistent notification. |
| `SealedBox.kt` | ECDH + AES‑256‑GCM envelope encryption. |
| `ChatActivity.kt` | Full chat UI: text/voice messages, multi‑select, ACK updates. |
| `MainActivity.kt` | Contact list, rename, delete, mesh status indicator. |
| `PairingActivity.kt` | QR scan, public key paste, 6‑digit short code pairing. |
| `AppDatabase.kt` / `MessageDao.kt` / `MessageEntity.kt` | Room database with migration support (version 2). |

---

## 🚧 Known Limitations (Transparent)

| Limitation | Impact | Roadmap |
| :--- | :--- | :--- |
| **Sender authentication is not cryptographically proven** | The packet's claimed sender ID is asserted, not proven. | Upgrade to Noise handshake (e.g., Noise_X). |
| **No store‑and‑forward queue** | Messages sent while no relay path exists are lost. | Add persistent pending queue with retries. |
| **BLE range limits** | BLE is short‑range (10–100 m). | Future: libp2p or Nostr fallback (optional). |
| **Voice messages are compressed** | 30s recordings are ~60‑100 KB. | Large file transfer may be added later. |

---

## 🎨 Theme

- **Background:** Dark gray (`#121212`)
- **Accent:** Orange (`#FFA500`) for text, borders, and read receipts.
- **Font:** Clean, bold, monospace‑style for the pairing code.

---

## 📦 Building & Installing

### Prerequisites
- Android Studio (latest recommended)
- Android SDK (API 26+)
- A physical device with BLE (emulators do not support BLE)

### Build
```bash
# Clean and build the debug APK
./gradlew clean assembleDebug

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk