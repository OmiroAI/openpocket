import importlib.util
import json
import os
from pathlib import Path
from unittest.mock import patch
from test_api import ApiTests

SCRIPT = Path(__file__).resolve().parents[2]/'integrations/hermes/open-pocket/scripts/jobs.py'
spec = importlib.util.spec_from_file_location('hermes_jobs', SCRIPT)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class HermesClientTests(ApiTests):
    def test_helper_claims_and_completes_real_api_job_without_printing_secret(self):
        job = self.upload().json()['job_id']
        with patch.dict(os.environ, {'OPENPOCKET_SERVICE_URL':'https://service.invalid',
                'OPENPOCKET_AGENT_TOKEN':self.config.agent_token,'OPENPOCKET_AUDIO_ROOT':self.temp.name,
                'OPENPOCKET_LEASE_DIR':self.temp.name+'/leases'}):
            client=module.Client()
            def request(path,body=None):
                response=self.client.get(path,headers=self.agent) if body is None else self.client.post(path,headers=self.agent,json=body)
                response.raise_for_status();return response.json()
            client.request=request
            claim=client.claim(job)
            self.assertNotIn('lease_token',claim)
            self.assertEqual(client.lease_file(job).stat().st_mode & 0o777,0o600)
            client.action(job,'heartbeat',{})
            result=client.action(job,'complete',{'result_reference':'test-result'})
            self.assertEqual(result['state'],'completed')
            self.assertFalse(client.lease_file(job).exists())

    def test_audio_endpoint_requires_agent_and_returns_original_bytes(self):
        job = self.upload().json()['job_id']
        path = '/v1/jobs/'+job+'/audio'
        self.assertEqual(self.client.get(path).status_code,401)
        self.assertEqual(self.client.get(path,headers=self.up).status_code,401)
        response = self.client.get(path,headers=self.agent)
        self.assertEqual(response.status_code,200)
        self.assertEqual(response.content,self.audio)
        self.assertEqual(response.headers['cache-control'],'no-store')
        self.assertEqual(response.headers['content-type'],'audio/mpeg')
        self.assertEqual(self.client.get('/v1/jobs/missing/audio',headers=self.agent).status_code,404)

    def test_remote_audio_download_is_private_and_verified(self):
        import io
        from unittest.mock import Mock
        job = self.upload().json()['job_id']
        with patch.dict(os.environ, {'OPENPOCKET_SERVICE_URL':'https://service.invalid',
                'OPENPOCKET_AGENT_TOKEN':self.config.agent_token,
                'OPENPOCKET_LEASE_DIR':self.temp.name+'/leases'},clear=True):
            client=module.Client()
            data=self.store.list_jobs('queued','',50)['jobs'][0]
            opener=Mock();opener.open.return_value=io.BytesIO(self.audio)
            with patch.object(module,'build_opener',return_value=opener):
                audio=client.fetch_audio(data)
            self.assertEqual(audio.read_bytes(),self.audio)
            self.assertEqual(audio.stat().st_mode & 0o777,0o600)
            audio.unlink()
            opener.open.return_value=io.BytesIO(b'corrupt')
            with patch.object(module,'build_opener',return_value=opener):
                with self.assertRaises(ValueError):client.fetch_audio(data)
            self.assertFalse(audio.exists())
            self.assertFalse(list(client.cache.glob('audio-*')))
