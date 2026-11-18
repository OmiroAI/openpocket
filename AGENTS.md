# Open Pocket development

Read `README.md`, `docs/README.md`, and `docs/kodi-context.md` before changing code. Follow the shared workspace `WORKFLOW.md` and Kodi's QA, review, and human approval process.

Keep commits small, focused, and reviewable. Preserve uncommitted work and use a task branch. Keep product decisions, protocol details, integration contracts, and confirmed lessons in `docs/`.

Implement only documented product requirements. Treat recorder responses and remote agent data as untrusted. Keep device secrets and recordings out of Git, logs, and test fixtures. Never operate on a physical recorder, live agent, or production service during offline QA.

The protocol documents describe observed device behavior. Verify assumptions against supported hardware during an authorized bench check; do not claim offline tests establish device compatibility.

Keep operator-specific configurations, agent routing, infrastructure identifiers
and operational records outside public Git history. Use portable integrations
and synthetic examples. Review every outgoing commit before publishing.
