"""Whisper-compatible HTTP adapter for a private, operator-configured server."""
import json
import os
import urllib.request
from urllib.parse import urlsplit
import uuid

ENDPOINT = os.environ["TRANSCRIPTION_ENDPOINT"]
MODEL = os.environ["TRANSCRIPTION_MODEL"]
url = urlsplit(ENDPOINT)
if (url.scheme != "https" and not (url.scheme == "http" and os.getenv("ALLOW_PRIVATE_HTTP") == "1")) or not url.hostname or url.username or url.password or url.query or url.fragment:
    raise ValueError("Configure an HTTPS endpoint or explicitly enable private HTTP")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def transcribe(path):
    boundary = uuid.uuid4().hex
    parts = []
    for key, value in (("model", MODEL), ("response_format", "json")):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="chunk.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode(),
                  path.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()])
    request = urllib.request.Request(ENDPOINT, data=b"".join(parts), headers={"Content-Type":f"multipart/form-data; boundary={boundary}"})
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=1800) as response:
        raw = response.read(1_000_001)
    if len(raw) > 1_000_000:
        raise ValueError("Provider response too large")
    result = json.loads(raw)
    if not isinstance(result["text"], str):
        raise ValueError("Invalid transcription response")
    return result
