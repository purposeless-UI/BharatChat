# BharatChat (native, BLE-only, multi-hop mesh)

Contact-gated messaging, open Bluetooth relay. No Nostr, no internet
dependency, no server of yours to run.

License: **GPLv3** — see `LICENSE.md` and `NOTICE.md`.

## The privacy model, precisely

- **Transport layer (who can relay a packet): open.** Any phone running
  BharatChat — contact or stranger — will carry an encrypted envelope
  toward its destination, exactly like real bitchat-android. This is
  what makes multi-hop possible: B, C, and D don't need to be your
  contacts to relay a message from A to E.
- **Content: always sealed, never readable by a relay.** Every message
  is individually encrypted (ECDH + AES-256-GCM, `crypto/SealedBox.kt`)
  to the real recipient's key alone. A relaying stranger's phone only
  ever holds opaque bytes.
- **Messaging layer (who you can compose a message to): gated.** The
  app only ever lets you open a chat with someone in your paired
  Contacts list (`Contacts.kt`, `PairingActivity.kt` — QR-code mutual
  pairing). A stranger relaying your bytes has no way to make you
  message them back.
- **Background relay: always visibly running, by Android's design, not
  a choice.** `MeshForegroundService.kt` keeps the mesh alive with a
  permanent notification ("BharatChat is running") — required by
  Android for any continuous BLE background work, not optional or
  hideable.

## What's real in this build

- `ble/BleGattServerManager.kt` / `ble/BleGattClientManager.kt` — real
  GATT peripheral + central roles, both running simultaneously, same
  structural pattern (advertising, MTU negotiation before service
  discovery, CCCD-based notifications) confirmed working in the real
  bitchat-android app.
- `ble/BlePacket.kt` — binary packet with sender/recipient peer IDs, a
  packet ID for dedup, and TTL for hop-limiting.
- `ble/SeenPacketCache.kt` — bounded, time-expiring dedup cache so
  relayed packets don't loop forever.
- `ble/BleMeshManager.kt` — the actual relay logic: on receiving a
  packet not addressed to you, decrement TTL and forward to every
  other currently-connected device except the one it came from
  (standard controlled flood).
- `MeshForegroundService.kt` / `MeshServiceHolder.kt` — one mesh
  connection shared across the whole app, kept alive in the background.

## Stated honestly — real gaps, not hidden

- **Sender authentication is not cryptographically proven yet.**
  `SealedBox` uses a fresh ephemeral key per message for forward
  secrecy, which means the packet's claimed sender ID is asserted, not
  proven the way the original bitchat whitepaper's Noise-X pattern
  proves it (which embeds the sender's identity inside the handshake
  ciphertext itself). Content confidentiality is real; "this message
  is definitely from who it claims" is not, yet. Flagged in detail in
  `BleMeshManager.kt`'s class comment — the fix is a real, scoped piece
  of future work, not something to quietly ship as solved.
- **No store-and-forward queue.** A message sent while no relay path
  exists to the recipient is currently lost, not retried later. Flagged
  at the exact spot in `ChatActivity.kt`.
- **Not yet run on a device.** Written and reviewed carefully against
  real, working reference patterns, but there is no Android toolchain
  or physical device available in the environment that wrote this
  code. Two real phones in the same room is the actual next test.

## Building

```bash
./gradlew assembleDebug
adb install build/outputs/apk/debug/app-debug.apk
```
