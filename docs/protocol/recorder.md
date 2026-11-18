# Recorder interface

This document records the device communication contract for the Android companion. Values and responses can vary with firmware. Treat incoming fields as untrusted and verify transfer behavior on supported hardware before claiming compatibility.

## Hardware and media

The recorder uses an Actions ATS3085S4 controller for audio, storage, BLE, and device control, with a separately powered W9118 Wi-Fi module for faster file transfer. Recordings are MP3 (MPEG-2 Layer III), 16 kHz, mono, 32 kbps constant bitrate. Device paths follow `/RECORD/YYYY-MM-DD/YYYYMMDDHHMMSS.mp3`. File commands use the timestamp without `.mp3`. Storage is reported in whole MiB (1,048,576 bytes); see [storage accounting](storage.md).

BLE is the control and event channel. It can also transfer media. Wi-Fi provides a direct SoftAP and TCP stream. The host phone receives the recording and is responsible for delivery to the chosen self-hosted agent; the recorder does not perform that delivery.

## BLE GATT

| Purpose | UUID | Properties |
| --- | --- | --- |
| Primary service | `001120a0-2233-4455-6677-889912345678` | Service |
| Audio stream | `001120a1-2233-4455-6677-889912345678` | Notify |
| Alternate command | `001120a2-2233-4455-6677-889912345678` | Write, write without response |
| Control and status | `001120a3-2233-4455-6677-889912345678` | Write without response, notify |

Subscribe to control notifications before issuing commands. Commands use `APP&...`; responses and events use `MCU&...`. Correlate responses with the active operation and handle unsolicited recording, disk, and timeout events.

## Binding and clock

For first-time pairing, generate a recorder-specific 16-character hex key and send `APP&SK&<key>`. After `MCU&SK&OK`, send `APP&T&<epoch_seconds>` and wait for `MCU&T&OK`. This sequence succeeded on an unpaired recorder with Pixel 7 Pro. Require positive acknowledgment before selecting the new recorder. Store the binding key securely. Never put real keys in source, logs, fixtures, or analytics. The key's first eight characters form the documented SoftAP password.

## Discovery and status

| Command | Expected response | Purpose |
| --- | --- | --- |
| `APP&BAT` | `MCU&BAT&<percent>` | Battery state |
| `APP&SPACE` | `MCU&SPA&<free_mib>&<total_mib>` | Storage state |
| `APP&FW` | `MCU&FW&<version>` | Controller firmware |
| `APP&WF` | `MCU&WF&<version>` | Wi-Fi firmware |
| `APP&MAC` | `MCU&MAC&<mac>` | Device address |
| `APP&GT` | `MCU&CT&<YYYYMMDDHHMMSS>` | Recorder clock |
| `APP&STE` | `MCU&STE&<0|1|2>` | Idle, recording, or call state |
| `APP&LIST_DIRS` | `MCU&DIRS&<folder>`, then summary | Directory enumeration |
| `APP&LIST&<folder>` | `MCU&F&<folder>&<file>&<seconds>`, then `MCU&LIST&<count>` | File enumeration |

`APP&STA`, `APP&STO`, `APP&PAU`, and `APP&RESU` control recording. The sync workflow must account for active recording, but must not stop it without an agreed product behavior. `APP&D&<filepath>` deletes a device file and must not be part of automatic sync without an explicit user requirement.

## BLE file transfer

Send `APP&U&<folder>&<file>&<offset>` on the control characteristic. `MCU&U&<size>&<offset>` announces the exact file size. Binary MP3 chunks arrive on the audio characteristic, often up to 244 bytes each. The documented device may repeat the final packet at a 4,096-byte flash block boundary. Reconstruction must account for that boundary behavior without discarding valid equal-valued audio packets elsewhere. Stop at the announced byte count and handle `MCU&OFF` or disconnection as a transfer ending. Validate the completed file before marking it synced.

## Wi-Fi file transfer

`APP&WIFI` reports `MCU&WIFI&<ssid>&<password>`. The documented SoftAP is on `192.168.200.0/24`, with the device at `192.168.200.1`; it does not provide internet access. For a fresh transfer, power-cycle the module with `APP&WIFIC`, then `APP&WIFIO` and wait for `MCU&WIFIO`. Connect the phone to the reported SoftAP and open TCP `192.168.200.1:8475`. Request the file over BLE with `APP&U&<folder>&<file>&0`; wait for `MCU&U&<size>&0`, then send `APP&U&WIFI` and wait for `MCU&U&WIFI`. Read the TCP byte stream, retain exactly the reported file size, and discard transport padding. Close the socket and send `APP&WIFIC` in cleanup, including error paths. The TCP listener is one-shot per Wi-Fi boot; a later file needs a fresh module session or a separately validated batch flow.

Avoid Wi-Fi transfer at 10% battery or below. Restore the phone's previous network after transfer so delivery to the agent can proceed. File transfer must leave the original recording on the device unless a separate, explicit deletion flow is specified.

## USB mode and firmware updates

`APP&USB&1` exposes storage over USB and disables normal recorder ownership of that storage; `APP&USB&0` restores it. Firmware update commands and USB mode switching are outside the initial sync scope and require separate requirements and hardware verification.

`APP&SPACE` returns free space first, total space second, in whole MiB. The
previous KB interpretation was incorrect. Convert with 1,048,576 bytes per unit;
calculate percentage from `(total - free) / total`. See [storage accounting](storage.md)
for the published capacity cross-check and measured example.
