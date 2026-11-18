# First public alpha

The acceptance scenario starts with a fresh checkout, a clean Android install, an owner's recorder, and a new destination. Complete it without developer provisioning, private files, or access to the development environment.

| Priority | Gap | Acceptance |
| --- | --- | --- |
| 1 | First-time recorder setup | A new owner can connect an eligible recorder using a documented pairing or owner credential flow. Explain the previously paired case and any data-loss implications before an irreversible step. Verify on the supported Pixel 7 Pro. |
| 2 | Reproducible self-hosting | A single guide takes the operator from empty directories to authenticated HTTPS delivery. Provide synthetic configuration examples. If advertising built-in transcription, ship a usable worker image with ffmpeg and a configurable provider adapter, then verify a generated recording produces a transcript and an agent can consume it. |
| 3 | Interruption and recovery | Exercise a mid-download disconnect, a lost upload receipt, a destination change with pending work, and a worker restart around a chunk checkpoint. Demonstrate that the input survives, delivery is deduplicated, and a held transcription is visible and recoverable. Test recorder deletion using disposable media. |
| 4 | Public release verification | Run Android build and unit tests, backend tests, and a synthetic worker integration test in offline CI. Complete independent review on the sanitized implementation, then publish a versioned, signed APK with a checksum and installation instructions. Keep signing material private. |

The README should identify supported hardware, foreground sync behavior, and what users can do with the first release. Link to one setup guide. Update the design and implementation plan to reflect the current navigation and transcription ownership.

After the first alpha, prioritize per-recording transcription and notes status, scoped read access for remote transcript consumers, and outage recovery diagnostics. Keep agent identities, destinations and routing policies in operator configuration.
