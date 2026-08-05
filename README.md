# BharatChat

**Hybrid Mesh + Internet Relay Messaging**  
*Decentralised, encrypted, and resilient – works with or without the internet.*

BharatChat is a fully decentralised messaging app that combines **BLE mesh networking** with **Nostr‑based internet relay**. Devices connect directly over Bluetooth for offline/local communication, and fall back to public Nostr relays when internet is available. No central servers, no phone numbers, no tracking – just your identity and your contacts.

[![Platform](https://img.shields.io/badge/platform-Android-green)](https://developer.android.com)

---

## 📱 Features

### Messaging
- ✅ **1‑to‑1 encrypted messaging** – ECDH + AES‑256‑GCM with forward secrecy (same key for both transport layers).
- ✅ **Delivery & read receipts** – two‑tick status (sent, delivered, read) with orange indicators, over both BLE and Nostr.
- ✅ **Voice messages** – record, send, and play back (up to ~30s). Voice messages are queued and retried like texts.
- ✅ **Multi‑select message deletion** – long‑press to select multiple messages, delete all at once.
- ✅ **Delete contact** – remove contact and wipe entire chat history.
- ✅ **Outbox retry (store‑and‑forward)** – messages queued when **both** Bluetooth and internet are unavailable are automatically retried when either becomes available.
- ✅ **FIFO message ordering** – every message is assigned a monotonic **sequence number** at the moment of send, guaranteeing that queued messages are delivered in the exact order they were sent, even across multiple offline/online cycles.

### Connectivity (Dual‑Layer)
- **BLE Mesh Networking** (offline/local):
    - BLE GATT Server + Client running simultaneously.
    - B.A.T.M.A.N.‑style routing with link quality tracking, expiry, and periodic HELLO messages.
    - Multi‑hop relaying (up to 7 hops) through intermediate devices.
    - Fragment reassembly, deduplication, adaptive relay probability, jittered relay.
    - RSSI‑weighted route quality and proactive route discovery (RREQ).
    - Duty‑cycled scanning (5s on / 2s off) to save battery.
- **Nostr Internet Relay** (online/global):
    - Connects to a configurable list of public relays (10+ relays).
    - Automatic fallback when Bluetooth is unavailable or out of range.
    - Works even when the other device is on a different network.
    - WebSocket reconnection with exponential backoff and health‑check monitoring.

### Reliability & Recovery
- ✅ **Instant network‑change detection** – reacts to `onAvailable` and `onCapabilitiesChanged` callbacks (validated internet).
- ✅ **Forced restart on reconnect** – the Nostr manager is fully stopped and recreated on internet restoration, eliminating stale WebSocket states.
- ✅ **Health‑check heartbeat** – if no relay is connected, the system forces a restart every 30 seconds (fallback, rarely needed).
- ✅ **Outbox processing lock** – prevents concurrent processing of the outbox, preserving FIFO order.
- ✅ **Atomic sequence numbers** – assigned synchronously on the main thread, guaranteeing chronological order even under heavy load.

### User Interface
- ✅ **Material Design 3** – modern, touch‑friendly dark theme with orange accent.
- ✅ **Pairing via QR code** – scan or paste a 66‑hex compressed public key.
- ✅ **6‑digit short code** – for already‑paired contacts (quick re‑pairing).
- ✅ **Contact management** – rename, delete, pair new contacts.
- ✅ **Smart notifications** – incoming messages trigger a notification **with sound** only if you are **not** already in that chat.
- ✅ **Voice recording UI** – modal with timer, large toggle button (🎤 / ⏹), and cancel option.
- ✅ **Smooth animations** – fade‑in for new messages, ripple effects on interactive elements.

### Background & Persistence
- ✅ **Foreground service** – keeps the mesh alive in the background with a persistent notification.
- ✅ **Automatic reconnect** – scanning and advertising restart when Bluetooth turns back on; Nostr reconnects on internet restoration.
- ✅ **Persistent message storage** – messages are saved to Room database even when the app is in the background.
- ✅ **Persistent outbox** – queued messages survive app restarts and device reboots.
- ✅ **Global fragment memory cap** – limits memory usage during reassembly to 1 MB.

---

## 🔐 Privacy Policy

### What this app does NOT do

- BharatChat does **not** operate any central server. We never receive or store your messages, contacts, or identity.
- BharatChat does **not** use your location. The Bluetooth scan permission is marked with `neverForLocation` in the app manifest – Android itself enforces that this permission cannot be used to derive your physical location.
- BharatChat does **not** include any analytics or advertising SDKs for user tracking.  
  *Note:* The app uses Google’s ML Kit for QR code scanning; this library may send anonymous usage and performance data to Google. We do not have access to this data, and it is not tied to your identity.

### What data exists, and where it stays

| Data | Where it is stored | Does it ever leave your device? |
|------|---------------------|--------------------------------|
| **Your identity key pair** | Encrypted local storage (EncryptedSharedPreferences) | No – never leaves your device. |
| **Your contacts** (people you have paired with) | Encrypted local database | No – contacts are never shared with anyone. |
| **Message content** (plaintext) | Held in memory only while the app is open | **Yes** – but **only** as end‑to‑end encrypted ciphertext, relayed over Bluetooth **or via public Nostr relays** toward the intended recipient. Neither intermediate Bluetooth nodes nor Nostr relays can decrypt or read the message content. |
| **Voice messages** (audio files) | Stored locally in the app’s cache directory; received files are also stored locally for playback | **Yes** – as encrypted payloads, sent together with the message. The audio file itself is encrypted before transmission. |
| **Message history** | Stored in a local Room database (encrypted at rest if device encryption is enabled) | No – never leaves your device. |
| **Nostr relay metadata** | None stored; connection is ephemeral | When using internet mode, your IP address, public key, and the encrypted message are sent to public Nostr relays. The relays only see encrypted blobs and cannot read content. They may log metadata (timestamp, relay IP) per their own policies. |

### Permissions this app requests, and why

| Permission | Purpose |
|------------|---------|
| **Bluetooth** (scan/advertise/connect) | Required to discover nearby devices and form the Bluetooth mesh network that delivers messages. This is the core function of the app. |
| **Camera** | Used **only** when you choose to scan a QR code to pair with a new contact. No images are stored or transmitted. |
| **Notifications** | Required to show the persistent foreground notification (“BharatChat is running”) while the Bluetooth mesh service is active in the background – Android mandates this for any app performing continuous Bluetooth work outside the foreground. |
| **Location** (pre‑Android 12 only) | Required by older Android versions as a technical prerequisite for Bluetooth scanning to function at all. This app **does not** read, store, or use your location for any purpose, and does not request this permission on Android 12 and newer. |
| **Microphone** | Used **only** when you tap the microphone button to record a voice message. Recordings are not stored after sending (except as part of the outgoing message history). |
| **Internet** | Used **only** to connect to public Nostr relays for internet‑based messaging. No other internet traffic is generated by the app. |

### Relaying other users' messages

#### Bluetooth Mesh
BharatChat’s Bluetooth mesh **may** use your phone to relay encrypted messages on behalf of other users – even users you have not paired with – so that messages can travel further than direct Bluetooth range.  
Your phone **only** handles opaque encrypted bytes that it cannot decrypt; it never has access to the content, sender identity, or recipient identity of relayed messages beyond a short technical routing identifier (packet ID). Relaying is automatic and cannot be turned off, as it is essential to the mesh network’s function.

#### Nostr Internet Relays
When internet mode is active, your device connects to a list of public Nostr relays. These relays receive your encrypted messages and forward them to the intended recipient.  
Relays are operated by third parties and may log connection metadata (e.g., IP address, timestamp) per their own privacy policies. We do not control or have access to these logs. The message payload itself is encrypted end‑to‑end and cannot be read by relay operators.

### Third‑party services used

| Service | Purpose | Data shared |
|---------|---------|-------------|
| **Google ML Kit** | QR code scanning in `PairingActivity` | Anonymous usage/performance data (opt‑out possible via Google Play settings). |
| **Public Nostr relays** | Internet message delivery | Encrypted message payloads, public key, IP address (relay logs – see relay policies). |

We do not sell, share, or otherwise distribute your personal data to any third party for marketing or advertising purposes.

---

## 📂 Architecture (Key Components)

| Component | Description |
| :--- | :--- |
| `BleMeshManager.kt` | Core BLE mesh logic (routing, HELLO, relay, ACKs, adaptive relay, jitter, RSSI weighting). |
| `NostrMeshManager.kt` | Nostr WebSocket manager: connects to relays, sends/receives events, handles ACKs with `"p"` tags. |
| `MeshServiceHolder.kt` | Orchestrates both managers, network listener, health checks, outbox retry, and message routing. |
| `OutboxStoragePersistence.kt` | Persistent outbox queue with sequence numbers, thread‑safe (synchronised). |
| `OutboxRetryScheduler.kt` | Periodic retry with FIFO sorting, processing lock, and break‑on‑failure for ordered delivery. |
| `ChatActivity.kt` | Full chat UI: text/voice, multi‑select, ACK updates, sequence number assignment on main thread. |
| `PairingActivity.kt` | QR scan, public key paste, short‑code pairing. |
| `AppDatabase.kt` / `MessageDao.kt` | Room database with version 4 (nullable `messageId`, unique index). |
| `voice/` package | Voice recording, playback, encoding, and incoming message handling. |

---

## 🚧 Known Limitations

| Limitation | Impact | Roadmap |
| :--- | :--- | :--- |
| **Sender authentication is not cryptographically proven** | The packet's claimed sender ID is asserted, not proven. | Upgrade to Noise handshake (e.g., Noise_X). |
| **BLE range limits** | BLE is short‑range (10–100 m). | Mesh routing already extends range; future: custom hardware or LoRa. |
| **Nostr relays are public** | Relays can see metadata (sender, receiver, timestamps) but not content. | Future: use private relays or tor. |
| **Voice messages are ~60‑100 KB** | May be slow over BLE. | Future: compress more aggressively or use lossy codecs. |
| **No battery‑level adaptive mode** | Power consumption is optimised, but not dynamically adjusted based on battery %. | Add battery‑aware power modes. |

---

## 🎨 Theme

- **Background:** Dark gray (`#121212`)
- **Accent:** Orange (`#FFA500`) for text, borders, and read receipts.
- **Font:** Clean, bold, monospace‑style for pairing codes.
- **Material Design 3** with smooth transitions and ripple feedback.

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

🤝 Contributing

We welcome contributions! Please read our CONTRIBUTING.md 
and CODE_OF_CONDUCT.md before submitting pull requests.


📧 Contact

For questions, feature requests, or security reports, please open an issue on GitHub or contact us directly at:
your.email@example.com


BharatChat – because communication should be free, private, and resilient.# Bharat-Chat
