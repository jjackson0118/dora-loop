#!/usr/bin/env python3
"""Build a deployment event from the captured previous release and Git history.

History is produced by the runner from the exact checkout. No Git installation
or repository credentials are needed on the deployment target.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re


def payload(history, previous, head, event_id, deployed_at, outcome, verification,
            service="dora-loop", environment="production"):
    if outcome not in {"SUCCESS", "FAILED_ROLLOUT", "ROLLED_BACK"}:
        raise ValueError("unknown deployment outcome")
    if verification not in {"VERIFIED", "UNVERIFIED"}:
        raise ValueError("unknown verification state")
    nodes = {}
    for row in history:
        sha = row["sha"]
        if not re.fullmatch(r"[0-9a-f]{40}", sha) or sha in nodes:
            raise ValueError("invalid or duplicate history commit")
        dt.datetime.fromisoformat(row["authoredAt"])
        nodes[sha] = row
    matches = [sha for sha in nodes if sha.startswith(previous)]
    if not re.fullmatch(r"[0-9a-f]{7,40}", previous) or len(matches) != 1:
        raise ValueError("previous release is missing or ambiguous in history")
    if head not in nodes:
        raise ValueError("deployed commit is absent from history")

    def ancestors(start):
        found, pending = set(), [start]
        while pending:
            sha = pending.pop()
            if sha in found:
                continue
            if sha not in nodes:
                raise ValueError("history is shallow or incomplete")
            found.add(sha)
            pending.extend(nodes[sha]["parents"])
        return found

    current_ancestors = ancestors(head)
    base = matches[0]
    if base not in current_ancestors:
        raise ValueError("previous release is not an ancestor; reconcile explicitly")
    changed = current_ancestors - ancestors(base)
    # Deterministic order is part of the replay contract.
    changes = [{"commitSha": sha, "authoredAt": nodes[sha]["authoredAt"]}
               for sha in sorted(changed)]
    dt.datetime.fromisoformat(deployed_at.replace("Z", "+00:00"))
    return {"id": event_id, "service": service, "environment": environment,
            "deployedAt": deployed_at, "outcome": outcome,
            "verification": verification, "changes": changes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("history")
    parser.add_argument("previous")
    parser.add_argument("head")
    parser.add_argument("event_id")
    parser.add_argument("deployed_at")
    parser.add_argument("outcome")
    parser.add_argument("verification")
    args = parser.parse_args()
    result = payload(json.loads(Path(args.history).read_text()), args.previous,
                     args.head, args.event_id, args.deployed_at,
                     args.outcome, args.verification)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
