#!/usr/bin/env python3
"""Guest driver for rehearse-deployment.py; never run on a serving demo host."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

ROOT, BASELINE, CANDIDATE = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
APP = Path("/opt/dora-loop")
TOKEN = Path("/etc/dora-loop/app.env")
EVIDENCE = {"scope": "isolated real target orchestrator; not GitHub/Tailscale transport", "scenarios": []}


def run(args, cwd=None, check=True):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError("command failed: " + args[0] + " (output withheld)")
    return result


def deploy(args, cwd):
    return run(["sudo", "-u", "deploy", "--", *args], cwd, check=False)


def query(sql):
    return run(["sudo", "-u", "postgres", "psql", "-d", "doraloop", "-Atc", sql]).stdout.strip()


def leaves(value):
    if isinstance(value, dict):
        return [item for v in value.values() for item in leaves(v)]
    if isinstance(value, list):
        return [item for v in value for item in leaves(v)]
    return [value]


def state():
    info = json.loads(urllib.request.urlopen("http://127.0.0.1:8080/actuator/info", timeout=5).read())
    readiness = urllib.request.urlopen("http://127.0.0.1:8080/actuator/health/readiness", timeout=5).status
    return {"current": os.readlink(APP / "current"), "last_good": os.readlink(APP / "last-good"),
            "info": info, "readiness": readiness}


class FaultListener(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/"):
            body, code = b'{"rehearsal":"injected product listener failure"}', 503
        else:
            with urllib.request.urlopen("http://127.0.0.1:8080" + self.path, timeout=5) as response:
                body, code = response.read(), response.status
        self.send_response(code)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def main():
    # Defense in depth: even direct invocation refuses any guest network interface.
    if set(os.listdir("/sys/class/net")) - {"lo"}:
        raise RuntimeError("guest has a network interface")
    original_token_file = TOKEN.read_bytes()  # never emitted or archived
    server = None
    try:
        for scenario, exit_code, outcome, verification in [
            ("smoke-defect", 1, "ROLLED_BACK", "VERIFIED"),
            ("inconclusive", 2, "SUCCESS", "UNVERIFIED"),
            ("delivery-failure", 2, "SUCCESS", "VERIFIED"),
        ]:
            case = ROOT / scenario
            case.mkdir()
            for folder in ["deploy", "gates"]:
                shutil.copytree(ROOT / folder, case / folder)
            shutil.copyfile(ROOT / "history.json", case / "history.json")
            shutil.copyfile(APP / "releases" / CANDIDATE / "app.jar", case / "app.jar")
            event_id = "rehearsal:" + scenario + ":" + str(time.time_ns())
            (case / "event-context.json").write_text(json.dumps({"head": CANDIDATE, "id": event_id}))
            if scenario == "delivery-failure":
                # Explicit harness seam: original gate runs; only afterward the file
                # credential changes. Running app retains its original environment.
                smoke = case / "gates/gates/smoke.sh"
                smoke.rename(smoke.with_name("smoke-original.sh"))
                smoke.write_text("""#!/usr/bin/env bash
set -euo pipefail
bash "$(dirname "$0")/smoke-original.sh"
python3 - <<'PY'
from pathlib import Path
import secrets
p = Path("/etc/dora-loop/app.env")
p.write_text("\\n".join("DORA_INGEST_TOKEN=" + secrets.token_hex(32) if line.startswith("DORA_INGEST_TOKEN=") else line for line in p.read_text().splitlines()) + "\\n")
PY
""")
            run(["chown", "-R", "deploy:dora", str(case)])
            reset = deploy(["bash", "deploy/remote-rollback.sh", str(APP / "releases" / BASELINE)], case)
            assert reset.returncode == 0, "baseline reset failed"
            before = state()
            assert before["current"] == str(APP / "releases" / BASELINE)
            assert before["last_good"] == before["current"]
            assert BASELINE in leaves(before["info"]), "baseline identity absent"
            before_count = int(query("SELECT count(*) FROM deployment_event"))
            url = "http://127.0.0.1:8080"
            if scenario == "smoke-defect":
                server = http.server.ThreadingHTTPServer(("127.0.0.1", 18080), FaultListener)
                threading.Thread(target=server.serve_forever, daemon=True).start()
                url = "http://127.0.0.1:18080"
            elif scenario == "inconclusive":
                url = "http://dora-rehearsal.invalid:8080"
                probe = run(["curl", "--noproxy", "*", "-sS", "-m", "5", url], check=False)
                assert probe.returncode == 6, "DNS injection did not yield curl 6"
            checksum = hashlib.file_digest((case / "app.jar").open("rb"), "sha256").hexdigest()
            result = deploy(["env", "NO_PROXY=*", "no_proxy=*", "SMOKE_SETTLE=4", "SMOKE_TIMEOUT=2",
                             "bash", "deploy/remote-orchestrate.sh", CANDIDATE, checksum, url], case)
            TOKEN.write_bytes(original_token_file)
            if server:
                server.shutdown()
                server.server_close()
                server = None
            assert result.returncode == exit_code, (scenario, result.returncode)
            after = state()
            expected = BASELINE if scenario == "smoke-defect" else CANDIDATE
            assert after["current"] == str(APP / "releases" / expected)
            assert after["last_good"] == after["current"]
            assert expected in leaves(after["info"])
            event = json.loads((case / "event.json").read_text())
            assert (event["outcome"], event["verification"]) == (outcome, verification)
            payload_hash = hashlib.sha256((case / "event.json").read_bytes()).hexdigest()
            report = json.loads((case / "reports/smoke.json").read_text())
            assert int(report["exit_code"]) == {"smoke-defect": 1, "inconclusive": 2, "delivery-failure": 0}[scenario]
            counts = [before_count, int(query("SELECT count(*) FROM deployment_event"))]
            replay_receipts = []
            if scenario == "delivery-failure":
                assert not (case / "event-receipt.json").exists()
                assert "DELIVERY_FAILED" in (case / "event-status").read_text()
                assert counts[-1] == before_count
                for disposition in ["STORED", "DUPLICATE"]:
                    replay = deploy(["python3", "deploy/report_event.py", "event.json", "event-receipt.json"], case)
                    assert replay.returncode == 0
                    replay_receipt = json.loads((case / "event-receipt.json").read_text())
                    assert replay_receipt["disposition"] == disposition
                    replay_receipts.append(replay_receipt)
                    assert hashlib.sha256((case / "event.json").read_bytes()).hexdigest() == payload_hash
                    counts.append(int(query("SELECT count(*) FROM deployment_event")))
                    assert counts[-1] == before_count + 1
            else:
                assert counts[-1] == before_count + 1
            stored = query("SELECT outcome || '/' || verification FROM deployment_event WHERE id='" + event_id + "'")
            assert stored == outcome + "/" + verification
            EVIDENCE["scenarios"].append({"scenario": scenario, "exit_code": result.returncode,
                "before": before, "after": after, "event": event, "smoke_report": report,
                "decision": (case / "decision").read_text().strip(),
                "status": (case / "event-status").read_text().strip(), "database_outcome": stored,
                "event_sha256": payload_hash, "event_counts": counts, "replay_receipts": replay_receipts,
                "receipt": json.loads((case / "event-receipt.json").read_text())})
            print(scenario + ": passed", flush=True)
    finally:
        TOKEN.write_bytes(original_token_file)
        if server:
            server.shutdown()
            server.server_close()
        (ROOT / "evidence.json").write_text(json.dumps(EVIDENCE, indent=2) + "\n")


if __name__ == "__main__":
    main()
