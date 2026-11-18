"""Single-owner backend transcription worker. The receipt database is read-only."""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
from urllib.parse import urlencode
import subprocess
import tempfile
import time
import urllib.request
import wave

DATA = Path("/data")
OUT = Path("/transcripts")
FRAMES = 900 * 16000


def save(path, value):
    temp = path.with_suffix(".tmp")
    with temp.open("w") as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def process(job, source, metadata, submit):
    dest = OUT / job
    dest.mkdir(exist_ok=True)
    if (dest / "v1.json").exists() or (dest / "hold.json").exists():
        return
    if (dest / "submitting.json").exists():
        save(dest / "hold.json", {"reason": "uncertain_submission"})
        return
    with source.open("rb") as audio:
        if hashlib.file_digest(audio, "sha256").hexdigest() != metadata["sha256"]:
            raise ValueError("source_integrity")
    with tempfile.TemporaryDirectory() as scratch:
        pcm = Path(scratch) / "decoded.wav"
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(source),
                        "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                        str(pcm)], check=True, stderr=subprocess.DEVNULL)
        segments = []
        with wave.open(str(pcm)) as decoded:
            offset = 0
            while samples := decoded.readframes(FRAMES):
                count = len(samples) // 2
                checkpoint = dest / f"chunk-{offset}.json"
                if checkpoint.exists():
                    segment = json.loads(checkpoint.read_text())
                    if segment["start_frame"] != offset or segment["frames"] != count:
                        raise ValueError("coverage_mismatch")
                else:
                    chunk = Path(scratch) / "chunk.wav"
                    with wave.open(str(chunk), "wb") as out:
                        out.setparams((1, 2, 16000, 0, "NONE", ""))
                        out.writeframes(samples)
                    save(dest / "submitting.json", {"start_frame": offset})
                    text = submit(chunk)["text"].strip()
                    if not text:
                        raise ValueError("no_usable_text")
                    segment = {"start_frame": offset, "frames": count, "text": text}
                    save(checkpoint, segment)
                    (dest / "submitting.json").unlink()
                segments.append(segment)
                offset += count
            if not segments or offset != decoded.getnframes():
                raise ValueError("incomplete_coverage")
        save(dest / "v1.json", {"job_id": job, "revision": 1, "metadata": metadata,
             "model": "whisper-large-v3-turbo", "sample_rate": 16000, "segments": segments})


def run():
    spec = importlib.util.spec_from_file_location("provider", "/provider/transcribe.py")
    provider = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(provider)
    config = json.loads(Path("/config/agent.json").read_text())
    rows = []
    after = ""
    while True:
        query = urlencode({"state":"queued", "after":after})
        request = urllib.request.Request(config["OPENPOCKET_SERVICE_URL"]+"/v1/jobs?"+query,
            headers={"Authorization":"Bearer "+config["OPENPOCKET_AGENT_TOKEN"]})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args):
                return None
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=10) as response:
            page = json.load(response)
        rows.extend(page["jobs"])
        after = page.get("next_after")
        if not after:
            break
    for row in sorted(rows, key=lambda item:item["metadata"]["duration_seconds"]):
        job, relative, attempts = row["id"], row["audio_path"], row["attempts"]
        if not re.fullmatch("[0-9a-f]{32}", job):
            continue
        dest = OUT / job
        if (dest / "v1.json").exists() or (dest / "hold.json").exists():
            continue
        if attempts and not (dest / "retry-approved.json").exists():
            continue
        # No state change or submission until the configured model is reachable.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(provider.ENDPOINT.rsplit("/", 2)[0]+"/models", timeout=5) as response:
            if not any(m["id"] == provider.MODEL for m in json.load(response)["data"]):
                return
        source = (DATA / relative).resolve()
        if source.parent != DATA / "audio":
            raise ValueError("invalid_audio_path")
        try:
            process(job, source, row["metadata"], provider.transcribe)
        except Exception as error:
            dest.mkdir(exist_ok=True)
            reason = str(error) if type(error) is ValueError and str(error) in {
                "source_integrity", "coverage_mismatch", "no_usable_text", "incomplete_coverage"
            } else "inspection_required"
            save(dest / "hold.json", {"reason": reason})
        return


if __name__ == "__main__":
    os.umask(0o077)
    with (OUT / ".worker.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        while True:
            try:
                run()
            except Exception as error:
                print("Worker waiting:", type(error).__name__, flush=True)
            time.sleep(10)
