# Kodi repository context

Read README.md and existing docs for architecture and product behavior.
Run `python .kodi/qa.py` for the declared offline documentation baseline. This
baseline does not prove Android behavior, radio compatibility, agent integration,
hardware behavior, or production readiness.

## Confirmed lessons

Kodi's repository QA runs from an exported source archive without `.git`. Checks
inside `.kodi/qa.py` must inspect the exported tree rather than call `git ls-files`.
Source: [onboarding PR #1](https://github.com/OmiroAI/openpocket/pull/1).
Verification: exact-commit independent QA on the configured QA host.

Add future lessons with source PR/comment URLs and verification. Keep this file
under 12,000 UTF-8 bytes. Never include credentials or raw logs.

## Agent handoff boundary

The owner replaced SFTP with a secure REST upload service that shares finalized
files with the agent and notifies it. Keep durable acceptance receipts separate
from agent processing status; retries must preserve delivery and job identity.
The service owns the jobs database; agent access is through its API.
Source: [PR #2 owner feedback](https://github.com/OmiroAI/openpocket/pull/2#discussion_r4597318079)
and the accompanying owner request for shared job/status access.
Verification: cross-document review of the proposed service contract; runtime
and agent integration verification remain pending implementation.
