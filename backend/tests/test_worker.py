import hashlib
import json
import shutil
import wave

import pytest
from openpocket_backend import transcription as worker


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="requires ffmpeg")
def test_chunk_coverage_and_uncertain_replay(tmp_path, monkeypatch):
    monkeypatch.setattr(worker, "OUT", tmp_path)
    monkeypatch.setattr(worker, "FRAMES", 16000)
    source = tmp_path/"synthetic.wav"
    with wave.open(str(source), "wb") as stream:
        stream.setparams((1, 2, 16000, 0, "NONE", ""))
        stream.writeframes(bytes(64002))
    meta = {"sha256":hashlib.sha256(source.read_bytes()).hexdigest()}
    def submit(chunk):
        return {"text":"synthetic"}
    worker.process("fixture", source, meta, submit, "test")
    result = json.loads((tmp_path/"fixture/v1.json").read_text())
    assert [part["frames"] for part in result["segments"]] == [16000,16000,1]
    def interrupted(chunk):
        raise TimeoutError()
    with pytest.raises(TimeoutError):
        worker.process("other", source, meta, interrupted, "test")
    worker.process("other", source, meta, interrupted, "test")
    assert (tmp_path/"other/hold.json").exists()
