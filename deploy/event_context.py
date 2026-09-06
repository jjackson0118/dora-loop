#!/usr/bin/env python3
"""Capture complete ancestry and the stable Actions attempt identity on runner."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    output = Path(sys.argv[1])
    sha = os.environ["GITHUB_SHA"]
    run = os.environ["GITHUB_RUN_ID"]
    attempt = os.environ["GITHUB_RUN_ATTEMPT"]
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not run.isdecimal() or not attempt.isdecimal():
        raise ValueError("invalid Actions deployment identity")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != sha:
        raise ValueError("checkout does not match deployment commit")
    shallow = subprocess.check_output(["git", "rev-parse", "--is-shallow-repository"], text=True).strip()
    if shallow != "false":
        raise ValueError("full history is required to report the deployed change range")
    log = subprocess.check_output(["git", "log", "--format=%H%x09%aI%x09%P", sha], text=True)
    history = []
    for line in log.splitlines():
        commit, authored, parents = line.split("\t")
        history.append({"sha": commit, "authoredAt": authored, "parents": parents.split()})
    (output / "history.json").write_text(json.dumps(history, sort_keys=True))
    (output / "event-context.json").write_text(json.dumps({
        "id": f"{repository}:run:{run}:attempt:{attempt}", "head": sha}, sort_keys=True))


if __name__ == "__main__":
    main()
