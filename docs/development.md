# Development and integration setup

## Local checks

Android: Java 17, Android SDK platform/build tools 35, then
`cd android && ./gradlew assembleDebug testDebugUnitTest`. SDK location belongs
in ignored `android/local.properties`. Install the debug APK through authorized
ADB access. The explicit test APK contains private provisioning and bench modes;
none of those modes run in offline QA or ship in the release APK.

Backend: `uv sync --locked --project backend`, then
`uv run --project backend pytest backend/tests`. No real recordings, device keys,
API credentials or deployment environment files belong in source control.

## Private Docker deployment

[Compose configuration](../deploy/compose.yaml) runs the backend without published
ports and nginx with one explicitly supplied private address. Set deployment
variables in a protected file outside the checkout:

- `OPENPOCKET_BIND_IP`: the host's Tailscale IP; never a wildcard address.
- `OPENPOCKET_HTTPS_PORT`: a free private port, default 8448.
- `OPENPOCKET_DATA_DIR`: private host directory owned by backend UID/GID 10000.
- `OPENPOCKET_ENV_FILE`: mode-0600 file containing distinct random
  `OPENPOCKET_UPLOAD_TOKEN` and `OPENPOCKET_AGENT_TOKEN`.
- `OPENPOCKET_TLS_DIR`: protected directory containing `server.crt` and `server.key`.

Issue the certificate for the full MagicDNS hostname with `tailscale cert`.
MagicDNS resolves the name; nginx terminates TLS. Neither Serve nor Funnel is
required. Mount certificate files read-only, preserve verification on the phone
and agent, and arrange certificate renewal plus an authorized nginx reload before
expiry. This test deployment does not install an automatic renewal timer.

Run Compose with the protected environment file and `deploy/compose.yaml`.
Use a separate runtime copy of the deployment source and persistent data outside
the development checkout. Do not run `down`, prune, or replace existing services
as a deployment shortcut. Redeployment and cleanup follow the host's authorization
boundaries. Keep the database, manifests and audio in consistent backups.

## Agent configuration

Install the whole [Open Pocket skill](../integrations/hermes/open-pocket/SKILL.md).
Set the service HTTPS URL and agent credential through environment variables or
an owner-only JSON file at `$HERMES_HOME/.config/openpocket/agent.json` (otherwise
`~/.config/openpocket/agent.json`). Keys match the environment variable names.
`OPENPOCKET_CONFIG` can select an explicit path. Never print the configuration.

With no `OPENPOCKET_AUDIO_ROOT`, the helper retrieves audio from the authenticated
jobs API into its private cache, verifies size and SHA256, then claims the job.
A shared audio root is optional. The agent does not receive database access.

Hermes can use two native recurring tasks:

1. A no-agent task running `scripts/notify.py`: batch new receipt notices and
   deliver them to the owner's configured notification destination. Empty checks
   are silent; no model is called. IDs are reserved before output so uncertain
   notification attempts are not blindly repeated. A crash between reservation
   and delivery may require operator reconciliation; this is not an exactly-once
   messaging guarantee. Hermes's delivery ledger is the send evidence.
2. A monitor task running `scripts/monitor.py`: invoke the agent only when queue
   identity changes. The prompt loads the Open Pocket and existing transcription
   skills, claims work, maintains its lease, saves results, then completes or fails
   the job. Completion/failure notices are separate from arrival notices.

Install launchers under Hermes's scripts directory using absolute skill/config
paths: scheduler execution may use a different HOME. Do not assume interactive
shell tests prove scheduled execution. The scripts are portable; local model and
transcription policy belongs to the agent, not the upload backend.

## Phone provisioning

The authorized debug runner's `agent` mode consumes an app-private
`files/agent-input.json` containing name, HTTPS URL and upload credential. Pass
that file over ADB stdin with mode 0600. The runner verifies the service, stores
credentials with Android Keystore, assigns the destination and sends one short
existing local recording twice to check receipt deduplication. It removes the
staged file in a finally block. Normal foreground sync subsequently uses that
saved destination. Never put the JSON or credential in Git or command arguments.

Example Agent can defer recording claims while its transcription dependency is unavailable.
Set OPENPOCKET_MODELS_URL to the trusted private model-list endpoint and
OPENPOCKET_MODEL to the required model ID in its private client configuration.
The queue monitor reports an empty runnable set during an outage and publishes
the queued IDs again on recovery. The claim helper also checks readiness before
fetching audio. A successful check cannot guarantee availability throughout a job.

## Release signing

Preview APKs are downloadable CI artifacts. For a stable update identity, the
maintainer creates and backs up a release keystore outside the checkout. Configure
the protected GitHub environment android-release with ANDROID_KEYSTORE_BASE64,
ANDROID_STORE_PASSWORD, ANDROID_KEY_PASSWORD and ANDROID_KEY_ALIAS. Require a
release reviewer, increment versionCode and versionName, then tag a reviewed main
commit with a v-prefixed version. The release workflow signs and verifies the APK
and attaches it and SHA256SUMS to a draft GitHub release.
