#!/usr/bin/env python3
"""Rehearse real target-side failures ONLY in the isolated dora-rehearsal VM."""
import argparse
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

BASELINE = "9a7159ec65eb2df1d2bba218d529edfb360affef"
CANDIDATE = "259a4f200215b15c622da727fe29d2b802fa1434"
GATES = "a780e1d80ef173d22c914d54c52e0eec2431a250"


def command(*args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, **kwargs).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=["dora-rehearsal"])
    parser.add_argument("--gates-repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    # Refuse before any guest mutation, including when its network is inherited.
    config = json.loads(command("lxc", "query", "/1.0/instances/" + args.target))
    if any(v.get("type") == "nic" for v in config.get("expanded_devices", {}).values()):
        raise SystemExit("refusing: rehearsal target has a NIC")
    if config.get("profiles"):
        raise SystemExit("refusing: rehearsal target has profiles")
    if args.output.exists():
        raise SystemExit("output must be a new directory")
    args.output.mkdir(parents=True)
    command("git", "-C", str(repo), "merge-base", "--is-ancestor", BASELINE, CANDIDATE)
    command("git", "-C", str(args.gates_repo), "cat-file", "-e", GATES + "^{commit}")
    history = []
    for line in command("git", "-C", str(repo), "log", "--format=%H%x09%aI%x09%P", CANDIDATE).decode().splitlines():
        sha, authored, parents = line.split("\t")
        history.append(dict(sha=sha, authoredAt=authored, parents=parents.split()))
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        # Fixed committed scripts/artifacts: the harness itself is the only added code.
        for source, ref, paths, destination in [
            (repo, CANDIDATE, ["deploy"], root),
            (args.gates_repo, GATES, ["gates", "lib"], root / "gates"),
        ]:
            destination.mkdir(exist_ok=True)
            archive = command("git", "-C", str(source), "archive", ref, *paths)
            with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
                bundle.extractall(destination, filter="data")
        (root / "history.json").write_text(json.dumps(history))
        driver = repo / "tools" / "rehearsal-target.py"
        (root / "rehearsal-target.py").write_bytes(driver.read_bytes())
        with tarfile.open(root / "bundle.tar", "w") as bundle:
            for name in ["deploy", "gates", "history.json", "rehearsal-target.py"]:
                bundle.add(root / name, arcname=name)
        remote = command("lxc", "exec", args.target, "--", "mktemp", "-d", "/tmp/dora-rehearsal.XXXXXXXX").decode().strip()
        command("lxc", "exec", args.target, "--", "chown", "root:dora", remote)
        command("lxc", "exec", args.target, "--", "chmod", "750", remote)
        command("lxc", "file", "push", str(root / "bundle.tar"), args.target + remote + "/bundle.tar")
        command("lxc", "exec", args.target, "--", "tar", "-xf", remote + "/bundle.tar", "-C", remote)
        result = subprocess.run(["lxc", "exec", args.target, "--", "python3", remote + "/rehearsal-target.py",
                                 remote, BASELINE, CANDIDATE], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (args.output / "execution.log").write_bytes(result.stdout)
        # Only the explicit evidence allowlist is collected; no credentials/configs.
        subprocess.run(["lxc", "file", "pull", args.target + remote + "/evidence.json",
                        str(args.output / "evidence.json")], check=True)
        if result.returncode:
            raise SystemExit("rehearsal failed; inspect execution.log and evidence.json")
    print("All three target-side rehearsals and stable replay passed:", args.output)


if __name__ == "__main__":
    main()
