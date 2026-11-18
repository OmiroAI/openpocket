"""Notification hints only; every agent claims work through the same jobs API."""
import json
from typing import Protocol
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NotificationAdapter(Protocol):
    def notify(self, event: dict) -> None: ...


class PollingAdapter:
    def notify(self, event):
        # The durable jobs API is the source of truth; polling needs no push hook.
        pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class WebhookAdapter:
    def __init__(self, url, token):
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            raise ValueError("Webhook requires an HTTPS URL without embedded credentials")
        if not token or "\n" in token or "\r" in token:
            raise ValueError("Webhook credential required")
        self.url, self.token = url, token

    def notify(self, event):
        request = Request(self.url, data=json.dumps(event).encode(), method="POST", headers={
            "Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with build_opener(NoRedirect()).open(request, timeout=10) as response:
            if not 200 <= response.status < 300:
                raise RuntimeError("Notification rejected")
