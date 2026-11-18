# MVP implementation plan

Status: proposed. Review [the design](../architecture/mvp-design.md) before resuming application work. Keep each implementation change small and reviewable. This plan does not authorize deployment of the upload service.

| Stage | Deliverable | Exit evidence |
| --- | --- | --- |
| 1. Design and contract | Agree the two screens, destination flow, HTTPS setup, and upload/receipt/jobs schema | Reviewed design and documented open decisions |
| 2. App foundation | Installable Android shell, private storage, persistent queue, protected secrets, two screens with synthetic data | Build and focused state tests; installation on Pixel 7 Pro; reviewed UI preview |
| 3. Recorder slice | Select/connect/reconnect, discover recordings, download a complete file, display progress and play it | Authorized bench checks on Pocket and Pixel; interrupted and repeated transfer cases |
| 4. Agent delivery | Build the upload service and jobs API; configure HTTPS/token access, shared files, notifications, and receipt reconciliation | Isolated service/agent contract tests covering lost acknowledgments, conflicts, and repeated submissions |
| 5. Lifecycle and management | Foreground retries, restart recovery, destination change confirmation, deletion, seven-day cleanup, diagnostics | Failure-path tests and device checks; no loss of undelivered media |
| 6. MVP acceptance | Full recorder → Android → Example Agent flow | Each v0.5 acceptance criterion recorded with evidence and remaining limitations |

## Test strategy

Use local unit tests for parsers, recording identity, destination binding, state recovery, and retention deadlines. Use generated media for transfer tests. Exercise HTTP completion, receipt races, and job claims in an isolated fixture before using the real agent. Device testing verifies permission flows, BLE behavior, lifecycle interruption, playback, and storage handling.

Test interruption during download, after local file finalization but before the database update, during upload, after remote publication but before receipt retrieval, and during destination changes. Test concurrent job claims, expired leases, stale completion updates, and missed notifications. Verify that files with equal sizes but different hashes cannot be mistaken for delivered copies. Verify that moving remote audio does not cause a second delivery after app restart.

Use a documented device test matrix for Pixel 7 Pro and the nearby Pocket, including recorder firmware and Android version. Never count local builds or emulator checks as physical-device acceptance. Do not delete real recordings to test an unverified command; use an explicitly designated disposable test recording.

## Inputs needed before end-to-end testing

The recorder's usable binding/setup route; an HTTPS service endpoint and scoped credentials; shared-directory access for Hermes; and an agreed notification/jobs integration. Keep actual credentials and private endpoint details outside Git. ADB access to Pixel 7 Pro is available, but installation and end-to-end checks have not been performed.

## Delivery

Develop locally in focused commits. Publish changes for Kodi's normal independent QA, different-model review, CI, and human approval when ready. The repository currently has documentation QA only; add Android build and behavioral checks before presenting implementation as accepted.

Record outcomes in `docs/`, including failed cases, compatibility limits, and verified fixes. Preserve the product boundary: Android transfers recordings; Hermes owns transcription, speaker identification, notes, and memory.
