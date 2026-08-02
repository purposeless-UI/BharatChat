# Notice

This project's Bluetooth mesh code (`src/main/java/com/droid/ble/`) was
written from scratch, but by directly studying the working patterns in
**bitchat-android** (https://github.com/permissionlesstech/bitchat-android,
GPLv3) — specifically its GATT service/characteristic structure,
advertising setup, MTU-negotiation-before-service-discovery sequencing,
and Android 12+ permission handling. None of bitchat-android's text was
copied verbatim, but the architecture and approach are directly derived
from it, so this project is licensed GPLv3 (`LICENSE.md`) to respect
that lineage rather than claim the design as fully independent.

Genuine differences from bitchat-android's BLE design:
- Distinct service/characteristic UUIDs — not meant to interoperate
  with open bitchat networks
- Gated discovery: advertised presence tags are derived per-contact via
  ECDH (only a paired contact can compute a match), rotated through in
  turn, instead of one openly-readable peer ID
- No multi-hop relay yet (see README) — direct single-hop connections only

Because this is GPLv3, anyone you distribute the APK to is entitled to
this project's complete source code. Keep this repository (or an
equivalent public source location) available alongside any APK release.
