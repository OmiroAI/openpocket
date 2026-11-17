# Recorder hardware reference

The documented recorder uses an Actions ATS3085S4 audio controller and a W9118 Wi-Fi module linked by SPI. The controller handles microphones, MP3 encoding, flash storage, BLE, clock, button, LED, and USB. The Wi-Fi module is normally off and starts on demand for direct file transfer.

| Component | Documented behavior |
| --- | --- |
| Acoustic microphone | Ambient speech capture |
| Contact microphone | Chassis vibration capture for call scenes |
| Storage | Approximately 59.6 MB recording partition, arranged by date |
| BLE | GATT command, events, and MP3 transfer |
| Wi-Fi | 2.4 GHz SoftAP; direct TCP stream on port 8475 |
| USB | Optional mass-storage mode; changes storage ownership |

The phone connects to the recorder locally and performs onward upload itself. The Wi-Fi module's power state is significant for battery life. Product code should release it after every transfer attempt and should avoid starting it at low battery.

The LED can indicate ready (blue), recording (amber), charging (blinking green), charged (green), or an error/update condition. Use protocol state and responses for app decisions rather than relying on LED appearance.

Hardware details can vary by device revision or firmware. Treat this file as a compatibility reference and validate on the actual recorder before expanding the support claim.
