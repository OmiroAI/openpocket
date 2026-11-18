# Set up Open Pocket

Install the preview APK or build from source using the README. In Settings choose **Find Pocket**, select your recorder and grant Nearby devices permission. Enter your own 16-character hexadecimal binding key under Device details when prompted. Keep the recorder nearby and idle. A previously bound recorder needs its existing credential. First-time owner key enrollment still needs independent hardware verification. A reset can erase audio. Preserve originals before considering any reset.

## Private HTTPS upload service

On a Linux host with Docker Compose and Tailscale, choose a private directory outside the checkout and run:

```sh
umask 077
mkdir -p data tls
sudo chown 10000:10000 data
printf 'OPENPOCKET_UPLOAD_TOKEN=%s\nOPENPOCKET_AGENT_TOKEN=%s\n' \
  "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > backend.env
```

Issue a trusted certificate for your own full MagicDNS hostname using `tailscale cert --cert-file tls/server.crt --key-file tls/server.key YOUR_HOSTNAME`. Keep the key private. Create a private Compose environment file with absolute paths:

```dotenv
OPENPOCKET_BIND_IP=YOUR_HOST_TAILSCALE_IPV4
OPENPOCKET_HTTPS_PORT=8448
OPENPOCKET_DATA_DIR=/absolute/private/data
OPENPOCKET_ENV_FILE=/absolute/private/backend.env
OPENPOCKET_TLS_DIR=/absolute/private/tls
```

From the checkout run `docker compose --env-file /absolute/private/compose.env -f deploy/compose.yaml up -d --build`. Connect the phone to your tailnet. In Settings enter an agent name, `https://YOUR_HOSTNAME:8448`, and the upload token. Keep the agent token on trusted server-side consumers. Record a short test clip, open Sync, then check Delivered for the receipt. Plan certificate renewal and backups before relying on the service.

See [development and integration](development.md) for agent setup and [worker setup](worker.md) for optional server-side transcription.
