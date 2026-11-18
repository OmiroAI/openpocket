"""Durable receipts and leased jobs. No agent-specific processing lives here."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
from contextlib import contextmanager


class Problem(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def fsync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def metadata(value):
    required = {"version", "recorder_id", "created_at", "device_timestamp", "duration_seconds", "size_bytes", "sha256"}
    if not isinstance(value, dict) or set(value) - {"media_type", "location"} != required or value.get("media_type", "audio/mpeg") not in ("audio/mpeg", "audio/mp4"):
        raise Problem(400, "invalid_metadata")
    if "location" in value:
        location = value["location"]
        if not isinstance(location, str) or not re.fullmatch(r"-?\d{1,2}\.\d{2},-?\d{1,3}\.\d{2}", location):
            raise Problem(400, "invalid_location")
        lat, lon = map(float, location.split(","))
        if abs(lat) > 90 or abs(lon) > 180:
            raise Problem(400, "invalid_location")
    if type(value["version"]) is not int or value["version"] != 1:
        raise Problem(400, "unsupported_version")
    for key in ("recorder_id", "created_at", "device_timestamp"):
        if not isinstance(value[key], str) or not 1 <= len(value[key]) <= 128 or any(ord(c) < 32 for c in value[key]):
            raise Problem(400, "invalid_metadata")
    if not re.fullmatch(r"[0-9a-f]{64}", str(value["sha256"])):
        raise Problem(400, "invalid_hash")
    for key in ("duration_seconds", "size_bytes"):
        if type(value[key]) is not int or value[key] < (1 if key == "size_bytes" else 0):
            raise Problem(400, "invalid_metadata")
    if value["duration_seconds"] > 7 * 86400:
        raise Problem(400, "invalid_duration")
    return value


class Store:
    def __init__(self, root, clock=time.time, lease_seconds=120):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        for directory in ("audio", "staging", "manifests"):
            (self.root / directory).mkdir(exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        self.clock, self.lease_seconds = clock, lease_seconds
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS recordings(
                    id TEXT PRIMARY KEY, receipt_id TEXT UNIQUE NOT NULL,
                    metadata TEXT NOT NULL, audio_path TEXT NOT NULL, accepted_at REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS jobs(
                    id TEXT PRIMARY KEY, recording_id TEXT UNIQUE NOT NULL REFERENCES recordings(id),
                    state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                    lease_token TEXT, lease_until REAL, updated_at REAL NOT NULL,
                    error_code TEXT, result_reference TEXT);
                CREATE TABLE IF NOT EXISTS notifications(
                    job_id TEXT PRIMARY KEY REFERENCES jobs(id), attempts INTEGER NOT NULL DEFAULT 0,
                    next_at REAL NOT NULL, delivered INTEGER NOT NULL DEFAULT 0);
            """)
            db.execute("INSERT OR IGNORE INTO settings VALUES('destination_id',?)", (secrets.token_hex(16),))
            self.destination_id = db.execute("SELECT value FROM settings WHERE key='destination_id'").fetchone()[0]
        self.recover()

    @contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.root / "jobs.sqlite", timeout=10)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA synchronous=FULL")
            try:
                db.execute("BEGIN IMMEDIATE")
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise
            finally:
                db.close()

    def _record(self, db, item):
        db.execute("INSERT INTO recordings VALUES(?,?,?,?,?)", (
            item["id"], item["receipt_id"], canonical(item["metadata"]), item["audio_path"], item["accepted_at"]))
        db.execute("INSERT INTO jobs(id,recording_id,state,updated_at) VALUES(?,?,'queued',?)", (item["job_id"], item["id"], self.clock()))
        db.execute("INSERT INTO notifications(job_id,next_at) VALUES(?,?)", (item["job_id"], self.clock()))

    def recover(self):
        # Only this service owns manifests. Never infer acceptance from file size alone.
        with self.db() as db:
            for manifest in (self.root / "manifests").glob("*.json"):
                item = json.loads(manifest.read_text())
                if db.execute("SELECT 1 FROM recordings WHERE id=?", (item["id"],)).fetchone():
                    continue
                meta = metadata(item["metadata"])
                audio = (self.root / item["audio_path"]).resolve()
                if audio.parent != self.root / "audio" or not audio.is_file():
                    raise RuntimeError("Incomplete durable upload journal; operator inspection required")
                with audio.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                if audio.stat().st_size != meta["size_bytes"] or digest != meta["sha256"]:
                    raise RuntimeError("Upload journal integrity mismatch")
                self._record(db, item)

    def lookup(self, recording_id):
        with self.db() as db:
            row = db.execute("SELECT r.*,j.id job_id,j.state job_state FROM recordings r JOIN jobs j ON j.recording_id=r.id WHERE r.id=?", (recording_id,)).fetchone()
            if row is None:
                raise Problem(404, "recording_not_found")
            return self.receipt(row)

    def receipt(self, row):
        return {"recording_id": row["id"], "destination_id": self.destination_id,
                "receipt_id": row["receipt_id"], "metadata": json.loads(row["metadata"]),
                "accepted_at": row["accepted_at"], "job_id": row["job_id"], "job_state": row["job_state"], "status": "accepted"}

    def job_audio(self, job_id):
        with self.lock, self.db() as db:
            row = db.execute("SELECT r.audio_path FROM jobs j JOIN recordings r ON r.id=j.recording_id WHERE j.id=?", (job_id,)).fetchone()
        if row is None:
            raise Problem(404, "job_not_found")
        path = (self.root / row["audio_path"]).resolve()
        if path.parent != self.root / "audio" or not path.is_file():
            raise Problem(404, "audio_unavailable")
        return path

    def accept(self, recording_id, meta, staging):
        with self.db() as db:
            row = db.execute("SELECT metadata FROM recordings WHERE id=?", (recording_id,)).fetchone()
            if row:
                if row[0] != canonical(meta):
                    raise Problem(409, "recording_conflict")
            else:
                stem = hashlib.sha256(recording_id.encode()).hexdigest()
                audio = self.root / "audio" / (stem + (".m4a" if meta.get("media_type") == "audio/mp4" else ".mp3"))
                manifest = self.root / "manifests" / (stem + ".json")
                # An uncommitted journal belongs to crash recovery, never overwrite it.
                if manifest.exists():
                    raise Problem(503, "recovery_required")
                os.replace(staging, audio)
                fsync_directory(audio.parent)
                item = {"id": recording_id, "receipt_id": secrets.token_hex(16),
                        "job_id": secrets.token_hex(16), "metadata": meta,
                        "audio_path": str(audio.relative_to(self.root)), "accepted_at": self.clock()}
                pending = manifest.with_suffix(".pending")
                with pending.open("w") as out:
                    out.write(canonical(item)); out.flush(); os.fsync(out.fileno())
                os.replace(pending, manifest)
                fsync_directory(manifest.parent)
                self._record(db, item)
        return self.lookup(recording_id)

    def list_jobs(self, state, after, limit):
        if state not in ("queued", "processing", "completed", "failed", "all"):
            raise Problem(400, "invalid_state")
        with self.db() as db:
            self._expire(db)
            rows = db.execute("""SELECT j.*,r.audio_path,r.metadata FROM jobs j JOIN recordings r ON r.id=j.recording_id
                WHERE j.id>? AND (?='all' OR j.state=?) ORDER BY j.id LIMIT ?""", (after, state, state, limit + 1)).fetchall()
            items = [self.job(row) for row in rows[:limit]]
            return {"jobs": items, "next_after": items[-1]["id"] if len(rows) > limit else None}

    def job(self, row):
        return {k: (json.loads(row[k]) if k == "metadata" else row[k]) for k in row.keys() if k != "lease_token"}

    def _expire(self, db):
        db.execute("UPDATE jobs SET state='queued',lease_token=NULL,lease_until=NULL,updated_at=? WHERE state='processing' AND lease_until<=?", (self.clock(), self.clock()))

    def action(self, job_id, action, data):
        with self.db() as db:
            self._expire(db)
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row:
                raise Problem(404, "job_not_found")
            now = self.clock()
            if action == "claim":
                if row["state"] != "queued":
                    raise Problem(409, "job_not_available")
                token = secrets.token_urlsafe(32)
                db.execute("UPDATE jobs SET state='processing',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=?,error_code=NULL,result_reference=NULL WHERE id=?", (token, now+self.lease_seconds, now, job_id))
                return {"job_id": job_id, "lease_token": token, "lease_until": now+self.lease_seconds}
            if action == "retry":
                if row["state"] != "failed":
                    raise Problem(409, "job_not_failed")
                db.execute("UPDATE jobs SET state='queued',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?", (now, job_id))
                return {"job_id": job_id, "state": "queued"}
            token = data.get("lease_token", "")
            if not isinstance(token, str) or not row["lease_token"] or not secrets.compare_digest(token, row["lease_token"]):
                raise Problem(409, "stale_claim")
            result = data.get("result_reference")
            error = data.get("error_code")
            if result is not None and (not isinstance(result, str) or len(result) > 512 or any(ord(c)<32 for c in result)):
                raise Problem(400, "invalid_result_reference")
            if action == "complete" and row["state"] == "completed" and row["result_reference"] == result:
                return {"job_id": job_id, "state": "completed"}
            if row["state"] != "processing":
                raise Problem(409, "stale_claim")
            if action == "heartbeat":
                db.execute("UPDATE jobs SET lease_until=?,updated_at=? WHERE id=?", (now+self.lease_seconds, now, job_id))
                return {"job_id": job_id, "lease_until": now+self.lease_seconds}
            if action not in ("complete", "fail"):
                raise Problem(404, "unknown_action")
            if action == "fail" and (not isinstance(error, str) or not re.fullmatch(r"[a-z0-9_]{1,64}", error)):
                raise Problem(400, "invalid_error_code")
            state = "completed" if action == "complete" else "failed"
            db.execute("UPDATE jobs SET state=?,error_code=?,result_reference=?,updated_at=? WHERE id=?", (state, error, result, now, job_id))
            return {"job_id": job_id, "state": state}

    def notify_pending(self, adapter):
        with self.db() as db:
            rows = db.execute("SELECT * FROM notifications WHERE delivered=0 AND next_at<=? LIMIT 20", (self.clock(),)).fetchall()
        for row in rows:
            try:
                adapter.notify({"event": "recording.accepted", "job_id": row["job_id"], "destination_id": self.destination_id})
            except Exception:
                with self.db() as db:
                    db.execute("UPDATE notifications SET attempts=attempts+1,next_at=? WHERE job_id=?", (self.clock()+min(3600, 2**min(row["attempts"],10)*5), row["job_id"]))
            else:
                with self.db() as db:
                    db.execute("UPDATE notifications SET delivered=1 WHERE job_id=?", (row["job_id"],))
