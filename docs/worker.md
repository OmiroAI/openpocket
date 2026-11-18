# Optional transcription

Start the upload service first. Provide a private Whisper-compatible endpoint accepting multipart WAV requests at /v1/audio/transcriptions and advertising your configured model at /v1/models. The worker image includes ffmpeg and the provider adapter.

Create an owner-only JSON file containing OPENPOCKET_SERVICE_URL and OPENPOCKET_AGENT_TOKEN. Use the service HTTPS URL and the agent credential from server setup. Make the file readable by container UID 10000. Add these values to the private Compose environment:

```dotenv
TRANSCRIPTION_ENDPOINT=https://speech.example.invalid/v1/audio/transcriptions
TRANSCRIPTION_MODEL=your-model-id
AGENT_CONFIG=/absolute/private/agent.json
AUDIO_DIR=/absolute/private/data/audio
TRANSCRIPT_DIR=/absolute/private/transcripts
```

Create the transcript directory, owned by UID 10000 with mode 0700. For an explicitly trusted private HTTP inference endpoint set ALLOW_PRIVATE_HTTP=1. From the checkout run `docker compose --env-file /absolute/private/compose.env -f deploy/transcriber.compose.yaml up -d --build`.

Mount the same transcript directory at the consumer's private openpocket/transcripts path. A finished job publishes v1.json with timed segments and original metadata. A hold.json means a human needs to inspect the available checkpoints before replay. See the [Hermes integration](../integrations/hermes/open-pocket/SKILL.md).
