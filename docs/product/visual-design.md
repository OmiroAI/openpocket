# Open Pocket interaction and visual design

## Direction

Open Pocket is a quiet utility for people who love the recorder and want their
own agent. The first interface pass made the branding, outlines and
buttons too dominant for repeated use. The owner requested a softer interface,
standard mobile bottom navigation, fewer steps and clearer feedback.

Retain a restrained blue accent, but prioritize recordings and transfer status:

- Background `#F7F8FA`, ink `#202631`, secondary text `#626A77`, accent `#3E5BC1`.
- Sentence-case system typography. Compact page titles replace promotional copy.
- White surfaces, subtle spacing and no heavy outlines or drop shadows.
- Blue marks actions and selection; delivery and errors also have text labels.
- Two persistent bottom destinations, Recordings and Settings: 24 dp vector icons,
  captions, a soft selected indicator and approximately 80 dp height. This follows
  the familiar icon-and-label navigation pattern, retaining the approved two-screen
  scope rather than adding destinations solely to meet a component guideline.
- At least 48 dp action targets, labeled inputs, selected navigation semantics,
  system/keyboard insets and scrollable details. No essential action is icon-only.

[Android navigation reference](https://developer.android.com/develop/ui/compose/components/navigation-bar).
The current app uses native Views; it does not introduce a Compose migration.

## Everyday flow

Recordings opens with connection/transfer status and an explicit reminder that
sync runs while the app is open. Progress is shown in place; routine percentage
updates do not rebuild the list. A compact agent-setup prompt explains why local
audio has not been delivered. It opens the Agent section of Settings directly.

Recordings are grouped by date, with time, duration and size. Playback is one tap
when a local copy exists; tapping a row opens details. Retry and destination
recovery actions appear where needed. History is always one tap in the top bar,
not at the bottom of a long list. Destructive actions remain in details with
explicit confirmation, including a stronger warning for undelivered originals.

Status copy distinguishes:

- Pending/download: audio is still being retrieved from Pocket.
- On phone: local copy exists; agent setup or delivery is pending.
- Delivered to agent: durable service acceptance, not completed transcription.
- Paused: a destination decision is needed; nothing is silently redirected.
- Failed: transfer error and an adjacent retry action.
- Local copy removed: delivery history remains; playback is unavailable here.

## Setup and settings

With a saved binding key, connecting from Recordings takes two in-app choices:
Connect Pocket, then select the device. Selection saves and connects immediately;
no extra Save action is required. Android permission/connection prompts remain
under system control. A permission result resumes the requested scan/action.

Settings presents recorder summary, agent and storage. Device address and key
are behind Device details; a saved key is acknowledged without displaying it.
Users without a provisioned key are directed to that form after selecting Pocket.
The Wi-Fi preference explains Bluetooth fallback and the observed Tailscale
limitation without suggesting the app changes VPN settings automatically.

A configured agent's form is collapsed behind Edit destination. An unconfigured
agent's form is open. Unsaved agent values survive tab switches within the
activity; secrets are not written to instance-state bundles or logs. Connection
checks and failures appear in the form. Changing destinations still asks for
confirmation and preserves existing queue assignments.

Storage describes the seven-day delivered-audio policy and offers explicit local
cleanup. It does not imply that undelivered audio can be automatically removed.

## Review and limits

Use the private-network preview of actual Pixel 7 Pro screens. Native frames are
1080 × 2340 pixels; the preview shows them at up to 360 CSS pixels wide with links
to the full-resolution image. Screenshots must not show credentials or audio
content. Keep artifacts outside Git in the dedicated preview directory.

Check populated Recordings, Settings, direct agent setup, tab switching with a
draft, and details scrolling. Hardware connectivity, VPN limitations and backend
acceptance retain their separate testing evidence. Large-font/TalkBack acceptance,
playback usability and long error states remain further review work; visual
inspection and a successful build do not establish those outcomes.

## Recording progress and batch actions

The connection card shows the number of safely downloaded recordings out of the
selected recorder's discovered library, plus a separate current-file transfer
indicator. Failed or active downloads do not count as complete. Delivered recordings
whose phone copies have been cleaned up retain their completed-download status.
Unknown file sizes are not used to invent an aggregate byte percentage.

Durations use explicit `hr`, `min`, and `sec` units and roll minutes into hours.
Status badges pair text with muted backgrounds: gray pending, blue transferring,
teal on phone, green delivered, amber destination paused, and red failed. Color is
never the only status indicator.

A standard top-bar overflow menu offers download new, upload ready, retry failed, delete delivered Pocket
originals, delete all listed Pocket originals, and remove delivered phone copies.
Deletion and cleanup sit in its “Manage recordings” submenu; the transfer card
contains no bulk-action button. Upload and retry act directly with a short notice.
Only destructive actions open a confirmation dialog. Confirmation snapshots the affected recordings. Uploads retain their assigned
destinations and exclude paused recordings. Deletion explicitly distinguishes
recorder originals from phone copies, counts unconfirmed deliveries, and excludes
recordings discovered after confirmation. It uses individual acknowledged deletions,
not a recorder reset. A failed deletion stops the batch and reports partial progress.
Leaving the foreground stops further actions; reopening does not resume a destructive
batch. The current file transfer finishes before a queued bulk action starts.

## Recorder capacity and transfer failures

Pocket storage is separate from download progress and phone storage. The connection
card and Pocket settings show device-reported percentage full, used/total/free
space in decimal MB/GB, and the last-check time. Refresh while connected; invalidate after a confirmed
recorder deletion or failed capacity query. A disconnected cached value retains its
check time. Unknown or invalid capacity is shown as unavailable, never zero/fullness
estimated from the downloaded library.

Failure labels name the transfer stage and endpoints: “Download failed · Pocket →
phone” versus “Upload failed · phone → Example Agent”. A network failure after a successful
download instead says “Upload waiting · Example Agent unreachable” and explains that the
phone copy is safe, Tailscale/agent connectivity is needed, and foreground retries
are automatic. The retry button names download or upload. Details and new history
entries retain the same distinction. Download progress counts locally saved files
even while their uploads wait. These labels do not assert Tailscale caused every
network failure; the server or another part of the network may also be unavailable.

### Bottom navigation update

Sync shows recorder connectivity and recordings awaiting delivery, including transfer progress and retries. Its check control stays in place during discovery and a small spinner occupies a reserved space beside the status. Delivered opens the history of confirmed agent receipts, with playback for available phone copies. Settings holds recorder and agent configuration. This three-destination layout supersedes the original two-screen navigation.
