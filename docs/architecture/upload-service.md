# Upload service and job tracking

Status: proposed API contract for review. The owner requested a secure REST service, shared audio storage, agent notification, and a small jobs database. This design replaces the earlier SFTP proposal.

## Components and access

One service owns a SQLite database and a private audio directory shared with Hermes. Android uploads over HTTPS through Tailscale. The agent reads finalized audio from the shared directory and uses the API to list jobs, claim work, and report results. It does not open or modify the SQLite file. This keeps transactions and state validation inside one process and avoids concurrent database access across network filesystems.

Use separate scoped credentials for Android upload/receipt access and agent job access. Bind each upload credential to an authorized destination. Android cannot mutate processing results; the agent cannot change delivery identity or receipts. Store credentials outside Git, protect them on Android with Keystore, and redact authorization headers from logs. Validate TLS certificates; tailnet connectivity alone does not replace API authentication.

The shared directory and database live on the server. Its path, ownership, permissions, backup policy, TLS setup, and notification hook are deployment inputs. Final audio is immutable to the uploader and readable by the agent. Do not store secrets or raw audio in database records or diagnostics.

## Minimal durable records

| Record | Data and purpose |
| --- | --- |
| Recording/receipt | Destination identity, recorder/recording identity, metadata, content hash, byte count, finalized relative path, accepted timestamp, receipt ID |
| Job | Job ID, recording reference, state, attempt count, lease token/expiry, created/updated timestamps, bounded error code, optional result reference |
| Notification | Job reference, pending/delivered state, retry count and next attempt |

A unique constraint on destination and recording identity makes submission idempotent. Reusing that identity with different content or immutable metadata returns a conflict. Retain receipt and job identity after processing or audio movement so retries cannot create new jobs. A failed job can be retried as another attempt on the same job.

Delivery is independent of processing: an accepted recording remains delivered even when processing is queued or fails. Android's seven-day cleanup timer starts at durable acceptance, not transcription completion. The existing two-screen UI may show the last known job status in recording details; that status is informational and must include its last refresh time.

## Proposed API

Paths and response schemas are provisional until reviewed. IDs are server-safe opaque values; server storage paths are never derived directly from client filenames.

| Endpoint | Behavior |
| --- | --- |
| `PUT /v1/recordings/{recordingId}` | Upload audio plus versioned metadata (recorder identity, raw timestamp and its interpretation, duration, size, SHA-256). Return the accepted receipt only after durability checks. |
| `GET /v1/recordings/{recordingId}` | Return the authenticated destination's receipt and associated job status, or not found. Android uses this to reconcile an uncertain upload. |
| `GET /v1/jobs?state=queued` | Agent lists eligible work with pagination. Include stable IDs and a relative finalized audio path. |
| `POST /v1/jobs/{jobId}/claim` | Atomically claim eligible work and return a lease token and expiry; conflict if another valid claim exists. |
| `POST /v1/jobs/{jobId}/heartbeat` | Extend the current lease using its token. |
| `POST /v1/jobs/{jobId}/complete` | Mark the current claim completed; accept repeated identical completion safely. |
| `POST /v1/jobs/{jobId}/fail` | Record a bounded failure reason for the current claim. |
| `POST /v1/jobs/{jobId}/retry` | Authorized agent/operator returns a failed job to queued without creating a new recording or receipt. |

The service returns success for an identical accepted upload without creating another job. It rejects identity/content conflicts, unauthorized access, oversized bodies, and invalid metadata explicitly. Temporary network or service failures are retryable; authentication, certificate, and identity conflicts require intervention. MVP can restart incomplete uploads from zero. Resumable HTTP uploads are deferred unless measured transfer sizes justify them.

## Acceptance and recovery

1. Authenticate and validate metadata, declared size, and limits before accepting the stream. Enforce limits during streaming too.
2. Stream to a private temporary file while calculating the hash. Do not expose partial audio to Hermes.
3. Verify the complete length and hash, flush the file, atomically finalize it on the same filesystem, and ensure directory metadata is durable.
4. Commit the receipt, queued job, and pending notification together in SQLite with appropriate durability settings. Only then return acceptance to Android.
5. Notify Hermes through the configured adapter. Hermes queries and claims the job before processing it.

Filesystem and database updates are not one atomic transaction. On restart, reconcile finalized files without receipts and reject incomplete or inconsistent entries. Never infer acceptance from filename or file size alone. A lost HTTP response is resolved by querying the receipt and matching the content identity.

Notifications may repeat or fail. Persist retries and let the agent poll the jobs API as a fallback. A notification never grants a claim. Expired claims can return to queued; stale workers cannot complete or renew them. Processing may run more than once after a worker crash, so Hermes must use the stable job ID to deduplicate downstream notes, memory updates, and notifications. Do not claim exactly-once external side effects from a database lease alone.

## Review and verification

Verify upload/receipt recovery, content conflicts, simultaneous duplicate submissions, agent access scopes, concurrent claims, lease expiration, stale completions, notification failure, and restart reconciliation using synthetic audio and a test agent. Agree the Hermes hook and result-reference format before end-to-end implementation. No live server deployment is part of this design PR.

## Remote agent audio access

`GET /v1/jobs/{job_id}/audio` requires the agent credential, returns the original accepted MPEG audio with `Cache-Control: no-store`, and returns 404 when a job or retained audio is unavailable. Upload credentials cannot read this endpoint. Agents must verify the metadata size and SHA256 before processing. The Hermes helper supports either a shared root or private authenticated download; the database is never mounted into the agent.
