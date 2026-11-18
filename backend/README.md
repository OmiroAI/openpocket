# Open Pocket backend

Agent-neutral HTTP upload, durable receipts, and a leased jobs API. Python 3.12+.

```sh
uv sync --locked --project backend
export OPENPOCKET_DATA_DIR=/absolute/private/openpocket-data
# Set distinct random OPENPOCKET_UPLOAD_TOKEN and OPENPOCKET_AGENT_TOKEN securely.
cd backend
uv run uvicorn openpocket_backend.app:from_env --factory --host 127.0.0.1 --port 8787 --workers 1 --limit-concurrency 32 --no-access-log
```

Use a trusted HTTPS reverse proxy on the private Tailscale network. Keep the backend loopback-only; do not expose a plain HTTP listener. Configure the proxy's upload limit and timeouts consistently with the service (100 MB and five minutes by default). Use one backend process per data directory. Bind-mount its `audio/` directory read-only into an agent environment if needed; never share the database itself. Back up the database and manifests together. No service deployment is performed by these instructions automatically.

`OPENPOCKET_MAX_UPLOAD_BYTES` and `OPENPOCKET_MAX_STORAGE_BYTES` bound media storage. Staging files from interrupted process crashes remain private and count toward the limit; inspect them during operator maintenance. Accepted receipts survive movement of audio, but a missing audio file can prevent an agent from processing its job. Do not remove unprocessed audio.

Agents can poll the jobs API. For notification hints, set `OPENPOCKET_NOTIFY_URL` to an operator-controlled HTTPS webhook and `OPENPOCKET_NOTIFY_TOKEN` to its credential. Redirects are rejected. Failed notifications retry with bounded backoff; jobs remain available for polling. Implement the `NotificationAdapter` protocol for another notification mechanism. No Hermes imports or model credentials exist in the backend.

Run `uv run --project backend pytest backend/tests` from the repository root. See [API contract](../docs/architecture/upload-service.md) and [integration setup](../docs/development.md).

For the private Docker deployment, use [the deployment guide](../docs/development.md). Agents can retrieve recording bytes through agent-authenticated `GET /v1/jobs/{job_id}/audio`; a shared volume is optional. The response disables HTTP caching.
