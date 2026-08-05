# BharatChat Privacy Policy

_Last updated: [Insert date]_

## 1. Introduction

BharatChat is a fully decentralised, encrypted messaging app that operates over Bluetooth Low Energy (BLE) mesh networking and, optionally, over public Nostr relays when internet is available. This privacy policy explains what data the app collects, where it is stored, and how it is used.

**We do not operate any central servers. We never receive or store your messages, contacts, or identity.**

---

## 2. Data We Do Not Collect

- We do not collect your location (the Bluetooth scan permission is marked with `neverForLocation`).
- We do not use analytics, advertising, or crash‑reporting SDKs that transmit data off your device.
- We do not sell, share, or trade your personal information with any third party for marketing purposes.

---

## 3. Data Stored Locally

All data is stored exclusively on your device, in encrypted local storage or a Room database. The following information is held locally:

| Data Type | Storage Location | Purpose |
|-----------|-------------------|---------|
| Your identity key pair | EncryptedSharedPreferences | Used to encrypt and sign your messages. Never leaves your device. |
| Contacts (paired public keys) | Local Room database | Allows you to send messages to paired contacts. Contacts are never shared. |
| Message history (plaintext) | Local Room database (encrypted at rest if device encryption is enabled) | Shows your chat history. Deleted when you delete the contact or the messages. |
| Voice message audio files | App cache directory and local storage | Used for recording and playback. Deleted when the message is deleted. |
| Outbox queue | Persistent local file | Temporarily stores messages that could not be sent immediately. Retried automatically when a connection becomes available. |

---

## 4. Data That Leaves Your Device

The only data that ever leaves your device is **end‑to‑end encrypted message payloads**, sent either via Bluetooth mesh or via public Nostr relays.

- **Bluetooth mesh:** Your phone may relay encrypted packets on behalf of other users. These packets contain only opaque encrypted bytes and cannot be decrypted by any intermediate node.
- **Nostr relays:** When internet mode is active, your device sends encrypted messages to public Nostr relays. The relays receive the encrypted payload, your public key, and the recipient’s public key (for routing), and they may log connection metadata (IP address, timestamp) per their own privacy policies. The content of the message remains encrypted and unreadable to the relay operators.

**No plaintext, no private keys, and no contact lists ever leave your device.**

---

## 5. Permissions Requested

| Permission | Purpose |
|------------|---------|
| **Bluetooth** (scan/advertise/connect) | Required to discover nearby devices and form the Bluetooth mesh network. |
| **Camera** | Used **only** when you scan a QR code to pair with a new contact. No images are stored or transmitted. |
| **Notifications** | Required to show the persistent foreground notification that keeps the mesh service running in the background. |
| **Location** (pre‑Android 12 only) | Required by older Android versions for Bluetooth scanning. The app does **not** read or use your location. On Android 12+ this permission is not requested. |
| **Microphone** | Used **only** when you record a voice message. Recordings are not stored after sending (except as part of your message history). |
| **Internet** | Used **only** to connect to Nostr relays for internet‑based messaging. No other internet traffic is generated. |

---

## 6. Third‑Party Services

The app uses the following third‑party libraries/services:

| Service | Purpose | Data Shared |
|---------|---------|-------------|
| **Google ML Kit** (barcode scanning) | Used for QR code scanning in the pairing process. May send anonymous usage and performance data to Google. You can opt out via Google Play settings. |
| **Public Nostr relays** | Used for internet‑based message delivery. Relays receive encrypted payloads, your public key, and IP address; they may log metadata according to their own policies. |

We do not control the data practices of these third‑party services. We encourage you to review their privacy policies.

---

## 7. Data Retention and Deletion

- All locally stored data (messages, contacts, voice files, outbox entries) remains on your device until you delete them manually (e.g., by deleting a contact or individual messages).
- The outbox queue is cleared automatically when messages are successfully sent.
- No data is retained on any server, because there is no central server.

---

## 8. Security

- All messages are end‑to‑end encrypted using ECDH + AES‑256‑GCM.
- Your private key never leaves your device.
- The Bluetooth mesh relays only encrypted data that cannot be decrypted by intermediate nodes.
- Communication with Nostr relays uses WebSocket (TLS) for transport security.

---

## 9. Children’s Privacy

BharatChat is not directed at children under the age of 13. We do not knowingly collect personal information from children. If you believe we have inadvertently collected such data, please contact us and we will take steps to delete it.

---

## 10. Changes to This Policy

We may update this privacy policy from time to time. Any changes will be posted on this page, and we encourage you to review it periodically. Your continued use of the app after any changes constitutes your acceptance of the updated policy.

---

## 11. Contact Us

If you have any questions, concerns, or requests regarding this privacy policy or your data, please contact us:

- Email: **[your.email@example.com](mailto:your.email@example.com)**
- GitHub Issues: [https://github.com/yourusername/bharatchat/issues](https://github.com/yourusername/bharatchat/issues)