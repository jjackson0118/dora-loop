#!/usr/bin/env python3
"""Deliver a saved event using the token kept on the deployment target.

On failure the caller retains the event for replay. Never print a token,
request headers, or an untrusted response body.
"""
import argparse
import http.client
import json
from pathlib import Path
import shlex
import time
import urllib.error
import urllib.request


def read_token(path):
    values = []
    for line in Path(path).read_text().splitlines():
        if line.startswith("DORA_INGEST_TOKEN="):
            words = shlex.split(line.split("=", 1)[1])
            if len(words) != 1 or not words[0]:
                raise ValueError("ingest token is empty or malformed")
            values.append(words[0])
    if len(values) != 1:
        raise ValueError("exactly one ingest token must be configured")
    return values[0]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def _open(request, timeout):
    # A redirect or ambient proxy must never receive the target's ingest token.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(request, timeout=timeout)


def deliver(event, token, endpoint, attempts=3):
    body = json.dumps(event, sort_keys=True).encode()
    for attempt in range(attempts):
        request = urllib.request.Request(endpoint, data=body, method="POST",
                  headers={"Content-Type": "application/json", "X-Dora-Ingest-Token": token})
        try:
            with _open(request, timeout=5) as response:
                code = response.status
                answer = json.loads(response.read(65536))
            if not isinstance(answer, dict):
                raise ValueError("ingest response must be an acknowledgment object")
            valid = ((code == 201 and answer.get("disposition") == "STORED")
                     or (code == 200 and answer.get("disposition") in {"DUPLICATE", "UPDATED"}))
            if not valid or answer.get("id") != event["id"]:
                raise ValueError("ingest response did not acknowledge this event")
            return {"id": event["id"], "disposition": answer["disposition"], "httpStatus": code}
        except urllib.error.HTTPError as exc:
            exc.close()
            if exc.code < 500:
                raise ValueError(f"ingest rejected event (HTTP {exc.code}); saved payload retained") from None
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            pass
        if attempt + 1 < attempts:
            time.sleep(1)
    raise ValueError("event delivery exhausted retries; saved payload retained")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("event")
    parser.add_argument("receipt")
    parser.add_argument("--token-file", default="/etc/dora-loop/app.env")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8080/api/v1/deployments")
    args = parser.parse_args()
    try:
        Path(args.receipt).unlink(missing_ok=True)
        event = json.loads(Path(args.event).read_text())
        receipt = deliver(event, read_token(args.token_file), args.endpoint)
        Path(args.receipt).write_text(json.dumps(receipt, sort_keys=True) + "\n")
    except (OSError, ValueError) as exc:
        # Exceptions are restricted to our messages or filesystem/JSON errors;
        # no server response body or authentication value is included.
        print("event delivery failed; saved payload retained (" + type(exc).__name__ + ")")
        return 1
    print("deployment event acknowledged: " + receipt["disposition"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
