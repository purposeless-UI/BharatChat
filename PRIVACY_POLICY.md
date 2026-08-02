# BharatChat Privacy Policy

_Last updated: [Insert date before publishing]_

## What this app does NOT do

- BharatChat does **not** have a server. We do not operate any backend, and we never receive or store your messages, contacts, or identity.
- BharatChat does **not** use your location. The Bluetooth scan permission is marked with `neverForLocation` in the app manifest – Android itself enforces that this permission cannot be used to derive your physical location.
- BharatChat does **not** include any analytics, advertising, or crash‑reporting SDKs that transmit data off your device.

## What data exists, and where it stays

| Data | Where it is stored | Does it ever leave your device? |
|------|---------------------|--------------------------------|
| **Your identity key pair** | Encrypted local storage (EncryptedSharedPreferences) | No – never leaves your device. |
| **Your contacts** (people you have paired with) | Encrypted local storage | No – contacts are never shared with anyone. |
| **Message content** (plaintext) | Held in memory only while the app is open | **Yes** – but **only** as end‑to‑end encrypted ciphertext, relayed over Bluetooth toward the intended recipient. No intermediate device (including relays) can decrypt or read the message content. |
| **Voice messages** (audio files) | Stored locally in the app’s cache directory; received files are also stored locally for playback | **Yes** – as encrypted payloads, sent together with the message. The audio file itself is encrypted before transmission. |
| **Message history** | Stored in a local Room database (encrypted at rest if device encryption is enabled) | No – never leaves your device. |

## Permissions this app requests, and why

| Permission | Purpose |
|------------|---------|
| **Bluetooth** (scan/advertise/connect) | Required to discover nearby devices and form the Bluetooth mesh network that delivers messages. This is the core function of the app. |
| **Camera** | Used **only** when you choose to scan a QR code to pair with a new contact. No images are stored or transmitted. |
| **Notifications** | Required to show the persistent foreground notification (“BharatChat is running”) while the Bluetooth mesh service is active in the background – Android mandates this for any app performing continuous Bluetooth work outside the foreground. |
| **Location** (pre‑Android 12 only) | Required by older Android versions as a technical prerequisite for Bluetooth scanning to function at all. This app **does not** read, store, or use your location for any purpose, and does not request this permission on Android 12 and newer. |
| **Microphone** | Used **only** when you tap the microphone button to record a voice message. Recordings are not stored after sending (except as part of the outgoing message history). |

## Relaying other users' messages

BharatChat’s Bluetooth mesh **may** use your phone to relay encrypted messages on behalf of other users – even users you have not paired with – so that messages can travel further than direct Bluetooth range.  
Your phone **only** handles opaque encrypted bytes that it cannot decrypt; it never has access to the content, sender identity, or recipient identity of relayed messages beyond a short technical routing identifier (packet ID). Relaying is automatic and cannot be turned off, as it is essential to the mesh network’s function.

## Open source

## Contact

For any privacy‑related questions or concerns, please contact:  
[Your email address or GitHub issues page URL]