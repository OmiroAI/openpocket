from starlette.testclient import TestClient
from openpocket_backend.app import create_app
from test_api import ApiTests as Fixture


def test_status_and_transcript_permissions():
    fixture = Fixture()
    fixture.setUp()
    try:
        job = fixture.upload().json()["job_id"]
        config = fixture.config
        config.transcripts=config.root/"transcripts"
        config.transcript_token="r"*32
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
