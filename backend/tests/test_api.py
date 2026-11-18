import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from starlette.testclient import TestClient
from openpocket_backend.app import Config, create_app
from openpocket_backend.store import Store, Problem


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = Config(Path(self.temp.name), "u"*32, "a"*32, 10000, 100000)
        self.now = 1000.0
        self.store = Store(self.temp.name, clock=lambda: self.now, lease_seconds=10)
        self.app = create_app(self.config, store=self.store)
        self.client = TestClient(self.app)
        self.audio = b'\xff\xf3' + bytes(range(256))*4
        self.meta = {"version": 1, "recorder_id": "synthetic-recorder", "created_at": "2026-01-01T12:00:00Z",
                     "device_timestamp": "20260101120000", "duration_seconds": 1,
                     "size_bytes": len(self.audio), "sha256": hashlib.sha256(self.audio).hexdigest()}
        self.up = {"Authorization": "Bearer "+self.config.upload_token}
        self.agent = {"Authorization": "Bearer "+self.config.agent_token}

    def tearDown(self):
        self.client.close(); self.temp.cleanup()

    def upload(self, identifier="sample", meta=None, content=None):
        headers = {**self.up, "Content-Type": "audio/mpeg", "X-Recording-Metadata": base64.b64encode(json.dumps(meta or self.meta).encode()).decode()}
        return self.client.put('/v1/recordings/'+identifier, headers=headers, content=self.audio if content is None else content)

    def call(self, job, action, body=None):
        return self.client.post(f'/v1/jobs/{job}/{action}', headers=self.agent, json=body or {})

    def test_accept_duplicate_and_processing_are_separate(self):
        first = self.upload(); self.assertEqual(first.status_code, 201)
        receipt = first.json(); job = receipt['job_id']
        second = self.upload(); self.assertEqual(second.json(), receipt)
        claim = self.call(job, 'claim').json()
        self.assertEqual(self.call(job, 'fail', {**claim, 'error_code': 'transcription_failed'}).status_code, 200)
        result = self.client.get('/v1/recordings/sample', headers=self.up).json()
        self.assertEqual(result['status'], 'accepted'); self.assertEqual(result['job_state'], 'failed')
        self.assertEqual(self.call(job, 'retry').status_code, 200)
        self.assertEqual(len(self.store.list_jobs('all','',100)['jobs']),1)

    def test_optional_capture_location_is_validated_and_preserved(self):
        for location in (None, "unknown", "91.00,0.00", "0.00,181.00"):
            self.assertEqual(self.upload(meta={**self.meta, "location": location}).status_code, 400)
        receipt = self.upload(meta={**self.meta, "location": "0.00,0.00"})
        self.assertEqual(receipt.status_code, 201)
        self.assertEqual(receipt.json()["metadata"]["location"], "0.00,0.00")

    def test_auth_scopes(self):
        self.assertEqual(self.client.get('/v1/info').status_code,401)
        self.assertEqual(self.client.get('/v1/info',headers=self.agent).status_code,401)
        self.assertEqual(self.client.get('/v1/jobs',headers=self.up).status_code,401)
        job=self.upload().json()['job_id']
        self.assertEqual(self.client.post(f'/v1/jobs/{job}/claim',headers=self.up).status_code,401)

    def test_phone_container_round_trip(self):
        meta = {**self.meta, "media_type": "audio/mp4"}
        self.assertEqual(self.upload(meta=meta).status_code, 415)
        headers = {**self.up, "Content-Type": "audio/mp4", "X-Recording-Metadata":
                   base64.b64encode(json.dumps(meta).encode()).decode()}
        receipt = self.client.put("/v1/recordings/sample", headers=headers, content=self.audio)
        self.assertEqual(receipt.status_code, 201)
        job_id = receipt.json()["job_id"]
        self.assertEqual(self.store.job_audio(job_id).suffix, ".m4a")
        audio = self.client.get(f"/v1/jobs/{job_id}/audio", headers=self.agent)
        self.assertEqual(audio.headers["content-type"], "audio/mp4")
        self.assertEqual(audio.content, self.audio)

    def test_same_size_different_content_is_conflict(self):
        self.upload()
        changed = {**self.meta,'sha256':hashlib.sha256(b'x'*len(self.audio)).hexdigest()}
        self.assertEqual(self.upload(meta=changed).status_code,409)

    def test_bad_hash_never_gets_receipt(self):
        self.assertEqual(self.upload(content=b'x'*len(self.audio)).status_code,422)
        self.assertEqual(self.client.get('/v1/recordings/sample',headers=self.up).status_code,404)
        self.assertEqual(list((Path(self.temp.name)/'staging').iterdir()),[])

    def test_concurrent_claim_and_lease_expiry(self):
        job=self.upload().json()['job_id']
        def claim():
            try: return self.store.action(job,'claim',{})
            except Problem as e: return e.status
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:claim(),range(2)))
        self.assertEqual(sum(isinstance(x,dict) for x in results),1)
        old=next(x for x in results if isinstance(x,dict))
        self.now+=11
        fresh=self.call(job,'claim').json()
        self.assertNotEqual(old['lease_token'],fresh['lease_token'])
        self.assertEqual(self.call(job,'complete',old).status_code,409)
        self.assertEqual(self.call(job,'heartbeat',fresh).status_code,200)
        complete={**fresh,'result_reference':'notes/job-1'}
        self.assertEqual(self.call(job,'complete',complete).status_code,200)
        self.assertEqual(self.call(job,'complete',complete).status_code,200)
        self.assertEqual(self.call(job,'complete',{**fresh,'result_reference':'other'}).status_code,409)

    def test_receipt_survives_agent_moving_audio_and_restart(self):
        original=self.upload().json()
        audio=next((Path(self.temp.name)/'audio').iterdir());audio.rename(Path(self.temp.name)/'processed.mp3')
        restarted=Store(self.temp.name)
        self.assertEqual(restarted.lookup('sample')['receipt_id'],original['receipt_id'])
        self.assertEqual(self.upload().status_code,200)

    def test_journal_replays_finalized_file_after_database_rollback(self):
        self.upload()
        with self.store.db() as db:
            db.execute('DELETE FROM notifications');db.execute('DELETE FROM jobs');db.execute('DELETE FROM recordings')
        recovered=Store(self.temp.name)
        self.assertEqual(recovered.lookup('sample')['status'],'accepted')
        self.assertEqual(len(recovered.list_jobs('queued','',100)['jobs']),1)

    def test_corrupt_journal_audio_is_not_accepted(self):
        self.upload()
        with self.store.db() as db:
            db.execute('DELETE FROM notifications');db.execute('DELETE FROM jobs');db.execute('DELETE FROM recordings')
        next((Path(self.temp.name)/'audio').iterdir()).write_bytes(b'corrupt')
        with self.assertRaises(RuntimeError):Store(self.temp.name)

    def test_notifications_retry_without_extra_jobs(self):
        self.upload()
        class Adapter:
            calls=0
            def notify(self,event):
                self.calls+=1
                if self.calls==1:raise OSError('temporary')
        adapter=Adapter();self.store.notify_pending(adapter);self.store.notify_pending(adapter)
        self.assertEqual(adapter.calls,1)
        self.now+=6;self.store.notify_pending(adapter);self.store.notify_pending(adapter)
        self.assertEqual(adapter.calls,2)

    def test_limits_and_metadata_rejected(self):
        self.assertEqual(self.upload(meta={**self.meta,'size_bytes':len(self.audio)+1}).status_code,400)
        self.assertEqual(self.upload(meta={**self.meta,'duration_seconds':-1}).status_code,400)
        self.assertEqual(self.upload(identifier='bad.name').status_code,400)
        self.assertEqual(self.client.get('/v1/jobs?limit=101',headers=self.agent).status_code,400)

    def test_listing_hides_claim_tokens_and_paginates(self):
        for n in range(3):self.upload(str(n))
        page=self.client.get('/v1/jobs?limit=2',headers=self.agent).json()
        self.assertEqual(len(page['jobs']),2);self.assertIsNotNone(page['next_after'])
        self.assertNotIn('lease_token',page['jobs'][0])
        next_page=self.client.get('/v1/jobs?after='+page['next_after'],headers=self.agent).json()
        self.assertEqual(len(next_page['jobs']),1)


if __name__=='__main__':unittest.main()
