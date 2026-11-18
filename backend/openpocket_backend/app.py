import asyncio
import base64
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import tempfile

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, FileResponse
from starlette.routing import Route
from starlette.concurrency import run_in_threadpool
from .store import Store, Problem, metadata
from .adapters import PollingAdapter, WebhookAdapter


@dataclass(frozen=True)
class Config:
    root: Path
    upload_token: str
    agent_token: str
    max_upload_bytes: int = 100_000_000
    max_storage_bytes: int = 10_000_000_000

    def __post_init__(self):
        if min(len(self.upload_token), len(self.agent_token)) < 32 or self.upload_token == self.agent_token:
            raise ValueError("Provide distinct upload and agent credentials of at least 32 characters")
        if not 1 <= self.max_upload_bytes <= self.max_storage_bytes:
            raise ValueError("Invalid storage limits")


def create_app(config: Config, adapter=None, store=None):
    store = store or Store(config.root)
    adapter = adapter or PollingAdapter()
    upload_lock = asyncio.Lock()

    def auth(request, role):
        expected = config.upload_token if role == "upload" else config.agent_token
        actual = request.headers.get("authorization", "")
        if not secrets.compare_digest(actual.encode(), ("Bearer " + expected).encode()):
            raise Problem(401, "unauthorized")

    def identifier(request):
        value = request.path_params["recording_id"]
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", value):
            raise Problem(400, "invalid_recording_id")
        return value

    async def info(request):
        auth(request, "upload")
        return JSONResponse({"api_version": 1, "destination_id": store.destination_id,
                             "max_upload_bytes": config.max_upload_bytes})

    async def lookup(request):
        auth(request, "upload")
        return JSONResponse(await run_in_threadpool(store.lookup, identifier(request)))

    async def upload(request: Request):
        auth(request, "upload")
        recording_id = identifier(request)
        header = request.headers.get("x-recording-metadata", "")
        try:
            if len(header) > 8192:
                raise ValueError()
            meta = metadata(json.loads(base64.b64decode(header, validate=True)))
            length = int(request.headers.get("content-length", "-1"))
        except (ValueError, TypeError, UnicodeError):
            raise Problem(400, "invalid_metadata") from None
        if length != meta["size_bytes"]:
            raise Problem(400, "length_required_or_mismatch")
        if length > config.max_upload_bytes:
            raise Problem(413, "upload_too_large")
        if request.headers.get("content-type") != "audio/mpeg":
            raise Problem(415, "expected_audio_mpeg")
        try:
            accepted = await run_in_threadpool(store.lookup, recording_id)
        except Problem as error:
            if error.status != 404:
                raise
        else:
            if accepted["metadata"] != meta:
                raise Problem(409, "recording_conflict")
            return JSONResponse(accepted)
        async with upload_lock:
            used = sum(p.stat().st_size for folder in ("audio", "staging") for p in (store.root / folder).iterdir() if p.is_file())
            if used + length > config.max_storage_bytes or shutil.disk_usage(store.root).free < length + 16_000_000:
                raise Problem(507, "storage_limit")
            fd, name = tempfile.mkstemp(prefix="upload-", dir=store.root / "staging")
            staging = Path(name)
            try:
                count = 0
                digest = hashlib.sha256()
                with os.fdopen(fd, "wb") as out:
                    async with asyncio.timeout(300):
                        async for chunk in request.stream():
                            count += len(chunk)
                            if count > length:
                                raise Problem(413, "upload_too_large")
                            await run_in_threadpool(out.write, chunk)
                            digest.update(chunk)
                    await run_in_threadpool(out.flush)
                    await run_in_threadpool(os.fsync, out.fileno())
                if count != length or digest.hexdigest() != meta["sha256"]:
                    raise Problem(422, "content_mismatch")
                result = await run_in_threadpool(store.accept, recording_id, meta, staging)
                return JSONResponse(result, status_code=201)
            except TimeoutError:
                raise Problem(408, "upload_timeout") from None
            finally:
                staging.unlink(missing_ok=True)

    async def jobs(request):
        auth(request, "agent")
        try:
            limit = int(request.query_params.get("limit", "50"))
        except ValueError:
            raise Problem(400, "invalid_limit") from None
        if not 1 <= limit <= 100:
            raise Problem(400, "invalid_limit")
        return JSONResponse(await run_in_threadpool(store.list_jobs, request.query_params.get("state", "queued"), request.query_params.get("after", ""), limit))

    async def audio_file(request):
        auth(request, "agent")
        path = await run_in_threadpool(store.job_audio, request.path_params["job_id"])
        return FileResponse(path, media_type="audio/mpeg", headers={"Cache-Control": "no-store"})

    async def action(request):
        auth(request, "agent")
        body = bytearray()
        async with asyncio.timeout(15):
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body) > 4096:
                    raise Problem(413, "request_too_large")
        try:
            data = json.loads(body or b"{}")
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise Problem(400, "invalid_json") from None
        return JSONResponse(await run_in_threadpool(store.action, request.path_params["job_id"], request.path_params["action"], data))

    async def notify_loop():
        while True:
            await run_in_threadpool(store.notify_pending, adapter)
            await asyncio.sleep(5)

    @asynccontextmanager
    async def lifespan(app):
        task = asyncio.create_task(notify_loop())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    async def problem(request, error):
        return JSONResponse({"error": error.code}, status_code=error.status)

    app = Starlette(routes=[Route("/v1/info", info),
        Route("/v1/recordings/{recording_id}", lookup, methods=["GET"]),
        Route("/v1/recordings/{recording_id}", upload, methods=["PUT"]),
        Route("/v1/jobs", jobs), Route("/v1/jobs/{job_id}/audio", audio_file), Route("/v1/jobs/{job_id}/{action}", action, methods=["POST"])],
        exception_handlers={Problem: problem}, lifespan=lifespan)
    app.state.store = store
    return app


def from_env():
    root = Path(os.environ["OPENPOCKET_DATA_DIR"])
    if not root.is_absolute():
        raise ValueError("OPENPOCKET_DATA_DIR must be absolute")
    config = Config(root, os.environ["OPENPOCKET_UPLOAD_TOKEN"], os.environ["OPENPOCKET_AGENT_TOKEN"],
                    int(os.getenv("OPENPOCKET_MAX_UPLOAD_BYTES", "100000000")),
                    int(os.getenv("OPENPOCKET_MAX_STORAGE_BYTES", "10000000000")))
    webhook = os.getenv("OPENPOCKET_NOTIFY_URL")
    adapter = WebhookAdapter(webhook, os.environ["OPENPOCKET_NOTIFY_TOKEN"]) if webhook else PollingAdapter()
    return create_app(config, adapter)
