#!/usr/bin/env python3
"""Stdlib client for the agent-neutral Open Pocket jobs API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError, URLError


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def load_config():
    """Optional operator-installed file; no secrets in command arguments."""
    path = Path(os.environ.get('OPENPOCKET_CONFIG', str(Path(os.environ.get('HERMES_HOME',str(Path.home())))/'.config/openpocket/agent.json')))
    if not path.exists():
        return
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Agent configuration must be owned by this user with mode 0600')
    config = json.loads(path.read_text())
    allowed = {'OPENPOCKET_SERVICE_URL','OPENPOCKET_AGENT_TOKEN','OPENPOCKET_AUDIO_ROOT','OPENPOCKET_LEASE_DIR','OPENPOCKET_MODELS_URL','OPENPOCKET_MODEL'}
    if not isinstance(config, dict) or set(config) - allowed or any(not isinstance(v,str) for v in config.values()):
        raise ValueError('Invalid agent configuration')
    for key,value in config.items():
        os.environ.setdefault(key,value)


class Client:
    def __init__(self):
        load_config()
        self.url = os.environ['OPENPOCKET_SERVICE_URL'].rstrip('/')
        parsed = urlsplit(self.url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('Use an HTTPS service URL without credentials, query, or fragment')
        self.token = os.environ['OPENPOCKET_AGENT_TOKEN']
        if '\n' in self.token or '\r' in self.token:
            raise ValueError('Invalid credential')
        scope = hashlib.sha256(self.url.encode()).hexdigest()[:20]
        self.cache = Path(os.environ.get('OPENPOCKET_LEASE_DIR', str(Path.home()/'.cache/openpocket-leases'))) / scope
        self.cache.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.cache, 0o700)

    def request(self, path, body=None):
        request = Request(self.url+path, data=None if body is None else json.dumps(body).encode(), headers={
            'Authorization': 'Bearer '+self.token, 'Content-Type': 'application/json'}, method='GET' if body is None else 'POST')
        with build_opener(NoRedirect()).open(request, timeout=30) as response:
            payload = response.read(1_000_001)
            if len(payload) > 1_000_000:
                raise ValueError('Response exceeds limit')
            return json.loads(payload)

    def transcription_ready(self):
        url = os.environ.get('OPENPOCKET_MODELS_URL')
        if not url:
            return True
        try:
            with build_opener(NoRedirect()).open(url, timeout=5) as response:
                payload = response.read(65537)
            if len(payload) > 65536:
                return False
            models = json.loads(payload)['data']
            return any(model['id'] == os.environ.get('OPENPOCKET_MODEL') for model in models)
        except (OSError, ValueError, KeyError, TypeError):
            return False

    def job(self, identifier):
        after = ''
        while True:
            page = self.request('/v1/jobs?'+urlencode({'state':'all','after':after}))
            for job in page['jobs']:
                if job['id'] == identifier:
                    return job
            after = page.get('next_after')
            if not after:
                raise ValueError('Job not found')

    def lease_file(self, job):
        if not re.fullmatch('[a-z0-9]{32}', job):
            raise ValueError('Invalid job ID')
        return self.cache/(job+'.json')

    def fetch_audio(self, job):
        identifier = job['id']
        self.lease_file(identifier)  # Validate the opaque identifier before constructing paths.
        size = job['metadata']['size_bytes']
        if not isinstance(size,int) or not 0 < size <= 100_000_000:
            raise ValueError('Invalid audio size')
        target = self.cache/(identifier+'.mp3')
        if target.exists():
            with target.open('rb') as source:
                if target.stat().st_size == size and hashlib.file_digest(source,'sha256').hexdigest() == job['metadata']['sha256']:
                    return target
        import tempfile
        fd,name = tempfile.mkstemp(prefix='audio-',dir=self.cache)
        temporary = Path(name)
        try:
            request = Request(self.url+'/v1/jobs/'+identifier+'/audio',headers={'Authorization':'Bearer '+self.token})
            digest = hashlib.sha256(); count = 0
            with os.fdopen(fd,'wb') as out, build_opener(NoRedirect()).open(request,timeout=30) as response:
                while chunk := response.read(65536):
                    count += len(chunk)
                    if count > size:
                        raise ValueError('Audio exceeds announced length')
                    out.write(chunk); digest.update(chunk)
                out.flush(); os.fsync(out.fileno())
            if count != size or digest.hexdigest() != job['metadata']['sha256']:
                raise ValueError('Audio integrity mismatch')
            os.replace(temporary,target)
            return target
        finally:
            temporary.unlink(missing_ok=True)

    def claim(self, identifier):
        if not self.transcription_ready():
            raise ValueError("Transcription unavailable. Recording remains queued.")
        job = self.job(identifier)
        shared = os.environ.get('OPENPOCKET_AUDIO_ROOT')
        if shared:
            root = Path(shared).resolve()
            audio = (root/job['audio_path']).resolve()
            if audio.parent != root/'audio' or not audio.is_file():
                raise ValueError('Shared audio is missing or outside the configured audio directory')
        else:
            audio = self.fetch_audio(job)
        with audio.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if audio.stat().st_size != job['metadata']['size_bytes'] or digest != job['metadata']['sha256']:
            raise ValueError('Shared audio integrity mismatch')
        return self.claim_verified(job, {'audio_path':str(audio)})

    def claim_transcript(self, identifier):
        self.lease_file(identifier)
        path = Path(os.environ['HERMES_HOME'])/'openpocket'/'transcripts'/identifier/'v1.json'
        revision = json.loads(path.read_text())
        job = self.job(identifier)
        if revision['job_id'] != identifier or revision['metadata'] != job['metadata'] or revision['revision'] != 1:
            raise ValueError('Transcript identity mismatch')
        return self.claim_verified(job, {'transcript_path':str(path)})

    def claim_verified(self, job, artifacts):
        identifier = job['id']
        claim = self.request('/v1/jobs/'+identifier+'/claim', {})
        path = self.lease_file(identifier)
        fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as out:
            json.dump(claim, out); out.flush(); os.fsync(out.fileno())
        return {'job_id':identifier, **artifacts, 'metadata':job['metadata'], 'lease_until':claim['lease_until']}

    def action(self, job, action, extra):
        body = {} if action == 'retry' else json.loads(self.lease_file(job).read_text())
        body.update(extra)
        result = self.request('/v1/jobs/'+job+'/'+action, body)
        if action in ('complete','fail'):
            self.lease_file(job).unlink(missing_ok=True)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['list','claim','claim-transcript','heartbeat','keepalive','complete','fail','retry'])
    parser.add_argument('job', nargs='?')
    parser.add_argument('--state', default='queued', choices=['queued','processing','completed','failed','all'])
    parser.add_argument('--result-reference')
    parser.add_argument('--error-code')
    args = parser.parse_args()
    client = Client()
    if args.action == 'list':
        after = ''
        while True:
            page = client.request('/v1/jobs?'+urlencode({'state':args.state,'after':after}))
            print(json.dumps(page))
            after = page.get('next_after')
            if not after: break
        return
    if not args.job:
        parser.error('job ID required')
    client.lease_file(args.job)
    if args.action == 'claim':
        result = client.claim(args.job)
    elif args.action == 'claim-transcript':
        result = client.claim_transcript(args.job)
    elif args.action == 'keepalive':
        while True:
            client.action(args.job,'heartbeat',{})
            time.sleep(30)
    else:
        extra = {}
        if args.action == 'complete': extra['result_reference'] = args.result_reference
        if args.action == 'fail':
            if not args.error_code: parser.error('--error-code required')
            extra['error_code'] = args.error_code
        result = client.action(args.job,args.action,extra)
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except HTTPError as error:
        print('Open Pocket API rejected request (HTTP '+str(error.code)+').', file=sys.stderr); sys.exit(1)
    except (URLError, OSError, ValueError, KeyError):
        print('Open Pocket request or local configuration failed. Check service reachability, credentials, lease, and shared files.',file=sys.stderr); sys.exit(1)
