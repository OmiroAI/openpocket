# Pocket Wi-Fi downloads

Wi-Fi is the preferred foreground recorder transport. Bluetooth remains the
control channel and fallback transport. The setting “Prefer Wi-Fi downloads” can
be disabled for Bluetooth-only use. Recordings and destinations retain the same
persistent state and delivery rules regardless of download transport.

## Connection and data flow

1. Authenticate over BLE and confirm the recorder is idle. Do not stop an active
   recording. Read battery level; use Bluetooth at 10% or below or unknown level.
2. Obtain hotspot credentials from the authenticated recorder in memory. Never
   persist or log them. Power the recorder Wi-Fi module off, then on, for each
   file because the TCP listener supports one session per boot.
3. Request that exact WPA2 network using Android `WifiNetworkSpecifier`, without
   internet capability. Android owns the connection consent dialog; allow up to
   90 seconds. Request Nearby Wi-Fi devices permission, without location use.
4. Bind only the recording socket to the supplied Android `Network`. Never bind
   the application process, change saved Wi-Fi networks, or change VPN settings.
   Connect to `192.168.200.1:8475`, populate the folder index over BLE, request the
   file size, then request the switch to the Wi-Fi stream.
5. Write exactly the announced bytes to an app-private temporary file. Reject
   invalid sizes and truncated streams, a 15-second socket stall, and a 30-minute
   overall stream deadline. Ignore padding beyond the payload. Flush
   to disk, validate Android media recognition and compute SHA256 before the
   existing atomic local-file promotion and database update.
6. On success, error or cancellation, close the socket, release the Android
   network request and attempt `APP&WIFIC` over BLE. Closing the request lets
   Android resume its normal network selection. Uploads use normal routing and
   existing retry logic; they never use the Pocket-bound socket.

A failed Wi-Fi attempt restarts the recording from byte zero over a fresh BLE
connection. Later files in the same cycle use Bluetooth. Wi-Fi attempts then pause for five
minutes to avoid repeated connection prompts. Partial files never
become delivered recordings. Foreground exit cancels the socket and requests
recorder Wi-Fi shutdown before closing BLE. If the recorder itself disconnects,
shutdown cannot be guaranteed; the next session starts with a power-off command.

Android can retain consent for subsequent requests to the same access point.
See [Android network request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
and [Nearby Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).

## Verification

Local JVM tests cover fragmented TCP input, exact payload lengths, padding,
truncated input, invalid sizes and interruption. The explicit Android `wifi`
bench mode compares two fresh Wi-Fi downloads with a BLE download of the same
recording and checks Android media recognition. Real audio remains in private
phone storage, never the source repository. 

## VPN compatibility

Android can refuse an explicitly bound local-network socket with `EPERM` while a
VPN is active. A VPN controls whether applications may use other networks
([Android VPN builder](https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass())).
Open Pocket never disconnects a VPN or changes its policy. If the network is
unavailable or access is refused, retain the recording and use Bluetooth; the
transfer history records the failure stage without hotspot credentials. On the
current Pixel bench, binding was refused while Tailscale was active. With the owner’s approval, pausing Tailscale allowed two Wi-Fi transfers to
complete and match the BLE payload. Tailscale was then reconnected and tailnet
ADB checked. The supported behavior on this configuration is Wi-Fi while the VPN
is paused, Bluetooth fallback while it is connected; concurrent Wi-Fi download
and Tailscale use is not established. Do not exclude the whole app from Tailscale
as a workaround: that would also remove its access to a tailnet-only agent.

## Command timeout diagnostics

Transfer history distinguishes folder-index and file-size response timeouts. These messages contain no recorder credentials, command payloads or
recording names. Both transports select the requested folder immediately before
requesting a file; discovery may have left a different folder selected. The folder
index gets the same 20-second response allowance as discovery. A missing size response still fails closed; it never promotes a partial recording.

