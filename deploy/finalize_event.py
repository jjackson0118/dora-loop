#!/usr/bin/env python3
"""Materialize final replayable evidence only after orchestration knows its result."""
import json
from pathlib import Path
import sys


def main():
    pair = tuple(sys.argv[1:])
    if pair not in {("SUCCESS", "VERIFIED"), ("SUCCESS", "UNVERIFIED"), ("ROLLED_BACK", "VERIFIED")}:
        raise ValueError("unsupported observed deployment result")
    event = json.loads(Path("event-draft.json").read_text())
    event["outcome"], event["verification"] = pair
    staged = Path("event.json.pending")
    staged.write_text(json.dumps(event, sort_keys=True) + "\n")
    staged.replace("event.json")
    Path("event-status").write_text("READY: final event saved; delivery not yet acknowledged\n")


if __name__ == "__main__":
    main()
