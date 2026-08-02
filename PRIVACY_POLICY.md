# BharatChat Privacy Policy

_Last updated: [fill in date before publishing]_

## What this app does NOT do

- BharatChat does not have a server, and does not send your messages,
  contacts, or identity to us or to any third party. There is no
  "us" collecting anything — there is no backend at all.
- BharatChat does not use your location. The Bluetooth scanning
  permission is marked `neverForLocation` in the app's configuration,
  meaning Android itself enforces that this app cannot use Bluetooth
  scan results to determine where you are.
- BharatChat does not use analytics, ads, or crash-reporting SDKs that
  transmit data off your device.

## What data exists, and where it stays

| Data | Where it's stored | Ever leaves the device? |
|---|---|---|
| Your identity key pair | Encrypted local storage on your phone | No |
| Your contacts (people you've paired with) | Encrypted local storage on your phone | No |
| Message content | Held in memory only while the app is open | Yes — but only as end-to-end encrypted ciphertext, relayed over Bluetooth toward the intended recipient. No message content is ever readable by any device it passes through except the actual recipient's. |

## Permissions this app requests, and why

- **Bluetooth (scan/advertise/connect):** required to discover nearby
  devices and form the mesh network that delivers messages. This is
  the core function of the app.
- **Camera:** used only to scan a QR code when you choose to pair with
  a contact. Not used at any other time, and no images are stored.
- **Notifications:** used to show the required persistent notification
  ("BharatChat is running") while the Bluetooth mesh service is active
  in the background — Android requires this for any app doing
  continuous Bluetooth work when not in the foreground.
- **Location (pre-Android-12 devices only):** required by older
  versions of Android as a technical prerequisite for Bluetooth
  scanning to function at all. This app does not read, store, or use
  your location for any purpose, and does not request this permission
  at all on Android 12 and newer, where it is not required.

## Relaying other users' messages

BharatChat's Bluetooth mesh may use your phone to relay an encrypted
message on behalf of other users, even ones you haven't paired with,
so that messages can reach further than direct Bluetooth range. Your
phone only ever handles opaque encrypted bytes it cannot read — it
never has access to the content, sender, or recipient identity of
relayed messages beyond a short technical routing identifier.

## Open source

BharatChat is open source (GPLv3). You can review exactly what the
code does at [your repository URL].

## Contact

[your contact email/repository issues page]
