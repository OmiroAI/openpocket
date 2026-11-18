#!/usr/bin/env python3
"""Cheap arrival notices for a Hermes --no-agent cron job. No transcript output."""
import fcntl
import json
import os
import tempfile
from urllib.parse import urlencode
from jobs import Client


def main():
    client = Client()
    path = client.cache/'announced.json'
    with (client.cache/'announced.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        seen = set(json.loads(path.read_text())) if path.exists() else set()
        jobs = {}; after = ''
        while True:
            page = client.request('/v1/jobs?'+urlencode({'state':'all','after':after}))
            jobs.update({job['id']:job for job in page['jobs']})
            after = page.get('next_after')
            if not after:
                break
        new = sorted(set(jobs)-seen)
        if not new:
            return
        # Reserve before emitting: uncertain delivery is not blindly repeated.
        fd,name = tempfile.mkstemp(prefix='announced-',dir=client.cache)
        try:
            with os.fdopen(fd,'w') as out:
                json.dump(sorted(seen|set(new)),out);out.flush();os.fsync(out.fileno())
            os.replace(name,path)
        finally:
            if os.path.exists(name):os.unlink(name)
        count=len(new)
        print(f'Open Pocket: {count} new recording'+('s' if count!=1 else '')+' safely received by your server. Example Agent tracks processing separately.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('Open Pocket arrival check failed; private configuration withheld')
