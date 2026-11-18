#!/usr/bin/env python3
"""Hermes monitor-script: stable queue identity, no audio or secrets on stdout."""
import json
from urllib.parse import urlencode
from jobs import Client


def main():
    client = Client()
    identifiers = []
    after = ''
    while True:
        page = client.request('/v1/jobs?'+urlencode({'state':'queued','after':after}))
        identifiers.extend(job['id'] for job in page['jobs'])
        after = page.get('next_after')
        if not after:
            break
    if identifiers and not client.transcription_ready():
        identifiers = []
    print(json.dumps({'queued_job_ids':sorted(identifiers)},sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('Open Pocket monitor unavailable; private configuration withheld')
