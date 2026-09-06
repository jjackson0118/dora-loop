#!/usr/bin/env python3
"""Prepare an explicitly pending event before deployment changes the target."""
import datetime as dt
import json
from pathlib import Path
import sys
from event_payload import payload


def main():
    context = json.loads(Path("event-context.json").read_text())
    if context["head"] != sys.argv[1]:
        raise ValueError("event context does not match deployment release")
    previous = Path("previous").read_text().strip().rsplit("/", 1)[-1]
    started = int(Path("started").read_text().strip())
    deployed_at = dt.datetime.fromtimestamp(started, dt.timezone.utc).isoformat()
    event = payload(json.loads(Path("history.json").read_text()), previous,
                    context["head"], context["id"], deployed_at, "SUCCESS", "UNVERIFIED")
    context.update({"previous": previous, "deployedAt": deployed_at})
    Path("event-context.json").write_text(json.dumps(context, sort_keys=True) + "\n")
    Path("event-draft.json").write_text(json.dumps(event, sort_keys=True) + "\n")
    Path("event-status").write_text("PENDING: no deployment outcome observed; draft must not be submitted\n")


if __name__ == "__main__":
    main()
