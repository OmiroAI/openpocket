# Pocket → Agent: Android Bridge

MVP requirements v0.5, supplied 2026-11-17. The owner has replaced the initial SFTP transport with a secure REST service and requested shared job tracking. Implementation details below remain proposals; see the [design review](../architecture/mvp-design.md).

## Goal and ownership

Open Pocket is an open-source Android companion for people who want to keep using their Pocket AI recorder without a subscription. Android handles recorder connectivity, reliable download, a local queue, and delivery to one configured self-hosted AI agent. The agent handles transcription, language hints, speaker recognition when possible, notes, action items, summaries, memory, and user interaction through its existing interface.

## Supported environment

- Pixel 7 Pro is the required live acceptance device; Pixel 7 and Pixel 9 may be used for development but are not acceptance gates for this MVP.
- The recorder uses the interface in `../protocol/`.
- Initial destination is Example Agent (Hermes) on a Linux server reached over Tailscale.
- HTTPS over Tailscale is the transport. A small upload service provides shared audio storage and a jobs/status API for the agent. Scoped token authentication is proposed.
- Sync runs while the app is open. Background sync and multiple Pocket devices are deferred.

## User flow

1. Record on Pocket, open Open Pocket, and connect to the recorder.
2. The app discovers recordings, downloads new ones, then uploads accepted local copies when the agent is reachable.
3. The agent processes completed uploads. The user continues to interact with the agent through its existing interface, such as Telegram.
4. No per-recording upload action or agent selection is needed during normal operation.

## Requirements

| ID | Required behavior |
| --- | --- |
| R1 | Connect and reconnect to the Pocket recorder. |
| R2 | List available recordings with timestamp, duration, and sync status. |
| R3 | Automatically download new recordings when connected and the app is open. |
| R4 | Allow confirmed manual deletion from Pocket; never automatically delete unsynced media. |
| R5 | Configure one active agent with name, HTTPS service address, and authentication; allow changes in Settings. The service configures the shared destination directory. |
| R6 | Automatically upload downloaded recordings when the agent is reachable. |
| R7 | Queue transfers, retry failures, and recover after network interruption or app restart. |
| R8 | Prevent duplicate uploads to the same destination. |
| R9 | Show pending, downloading, downloaded, uploading, delivered, and failed states. |
| R10 | Bind each queued recording to its destination so a Settings change cannot silently redirect it. |
| R11 | Show a recording list with transfer status. |
| R12 | Show recording timestamp, duration, file size, and destination. |
| R13 | Keep local audio until confirmed delivery; allow manual cleanup. |
| R14 | Use application-private storage and protected credential storage. |
| R15 | Reach the agent through Tailscale. |
| R16 | Automatically remove local audio seven days after confirmed delivery. |
| R17 | Play back a downloaded recording in the app. |
| R18 | Show simple transfer diagnostics and history without secrets or media payloads. |

## Interface

Two screens: **Recordings** (recorder connection, recordings, progress, retry, playback, and confirmed deletion) and **Settings** (recorder setup, agent destination and credentials, storage). No notes browser, transcript editor, or chat UI.

## Agent service handoff

Upload original audio and recording ID, creation timestamp, duration, size, and content hash to the service. It stages and validates the upload, finalizes the file in the shared directory, and records a durable receipt and queued job in its database. Android confirms delivery from the receipt. The service notifies the agent; the agent can also discover jobs through the API, claim them, and report processing status. A lost notification or processing failure must not cause duplicate audio delivery.

Keep actual credentials, private endpoint details, and recordings outside Git. See the [service design](../architecture/upload-service.md) for the proposed contract.

## Out of scope

On-device transcription or LLM inference; vocabulary configuration; Android speaker enrollment; notes and knowledge management; multiple simultaneous destinations; real-time streaming; multi-user accounts; background sync when the app is closed.

## Acceptance

1. Install and run via ADB on Pixel 7 Pro.
2. Connect to Pocket and discover recordings.
3. Download new recordings automatically while connected.
4. Upload downloaded recordings to the configured upload service automatically.
5. Recover interrupted transfers without data loss or duplicate delivery.
6. Persist sync state across app restarts.
7. Keep queued recordings bound to their original destination after Settings changes.
8. Safely delete a delivered recording from Pocket after user confirmation.
9. Verify the full flow with the nearby Pocket paired to Pixel 7 Pro.
10. Verify seven-day local cleanup, playback, and diagnostics without leaking credentials or audio.
11. The agent can list and claim accepted jobs and report processing status. Duplicate submissions and missed notifications do not create duplicate jobs.

## Deferred

Background Pocket sync and support for multiple Pocket devices.
