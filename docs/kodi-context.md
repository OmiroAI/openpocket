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
