from starlette.testclient import TestClient
from openpocket_backend.app import create_app
import test_api
from dataclasses import replace


def test_status_and_transcript_permissions():
    fixture = test_api.ApiTests()
    fixture.setUp()
    try:
        job = fixture.upload().json()["job_id"]
        config = replace(fixture.config, transcripts=fixture.config.root/"transcripts", transcript_token="r"*32)
        client=TestClient(create_app(config,store=fixture.store))
        path="/v1/recordings/sample/progress"
        assert client.get(path,headers=fixture.up).json()["stage"]=="waiting_for_transcription"
        directory=config.transcripts/job
        directory.mkdir(parents=True)
        (directory/"v1.json").write_text('{"revision":1}')
        assert client.get(path,headers=fixture.up).json()["stage"]=="transcript_ready"
        url=f"/v1/jobs/{job}/transcript"
        assert client.get(url,headers=fixture.up).status_code==401
        assert client.get(url,headers={"Authorization":"Bearer "+config.transcript_token}).json()=={"revision":1}
        client.close()
    finally:
        fixture.tearDown()
