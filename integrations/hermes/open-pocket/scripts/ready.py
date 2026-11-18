"""Monitor queued agent jobs with published backend transcripts."""
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlencode

root = Path(os.environ.get("HERMES_HOME", "/opt/data"))
sys.path.insert(0, str(root/"skills/media/open-pocket/scripts"))
from jobs import Client


def main():
    client = Client()
    after = ""
    ready = []
    while True:
        page = client.request("/v1/jobs?"+urlencode({"state":"queued", "after":after}))
        for job in page["jobs"]:
            if (root/"openpocket/transcripts"/job["id"]/"v1.json").is_file():
                ready.append(job["id"])
        after = page.get("next_after")
        if not after:
            break
    print(json.dumps(sorted(ready)))


if __name__ == "__main__":
    main()
