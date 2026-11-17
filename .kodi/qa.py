"""Offline documentation baseline for Open Pocket onboarding."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    ROOT / "README.md",
    ROOT / "AGENTS.md",
    ROOT / "docs/README.md",
    ROOT / "docs/security.md",
    ROOT / "docs/protocol/recorder.md",
    ROOT / "docs/protocol/hardware.md",
    ROOT / "docs/protocol/sync-workflow.md",
    ROOT / "docs/kodi-context.md",
]
for path in REQUIRED:
    assert path.is_file() and path.read_text().strip(), f"Missing document: {path}"

for path in REQUIRED:
    for target in re.findall(r"\[[^]]+\]\(([^)]+)\)", path.read_text()):
        if "://" not in target and not target.startswith("#"):
            linked = (path.parent / target.split("#", 1)[0]).resolve()
            assert linked.is_file() and linked.is_relative_to(ROOT), f"Broken link: {path}: {target}"

media_suffixes = {".mp3", ".wav", ".m4a", ".aac", ".flac"}
assert not any(path.suffix.lower() in media_suffixes for path in ROOT.rglob("*")), "Exported recorder media"
print("Documentation, local links, and exported media baseline passed.")
