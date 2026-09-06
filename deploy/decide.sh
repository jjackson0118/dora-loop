#!/usr/bin/env bash
# Decide what to do after the smoke gate, by reading its report.
#
#   decide.sh <path to smoke.json> <epoch seconds recorded before the deploy>
#
# It reads the gate's own JSON report rather than the CI step's status, because
# ci/run-gate.sh maps BOTH exit 1 and exit 2 to failure for the orchestrator --
# an orchestrator has two states and the contract has four. By the time a
# workflow step has a status, the distinction this decision turns on is gone.
#
# Exits mirror the gate contract deliberately:
#   0  keep the release; smoke verified it
#   1  ROLL BACK; smoke found a defect in what was deployed
#   2  keep the release, UNVERIFIED; smoke could not find out -- fail the job
#
# ONLY a well-formed, fresh, smoke-gate report carrying exit_code 0 produces
# exit 0. Everything else is 1 or 2, both of which fail the job.
#
# That sentence is the whole fix. The first version parsed with
# `grep -oE '"exit_code":[0-9]+'`, which emits every match in the file; `case`
# then compared a multi-line string and fell through to the catch-all. A
# reviewer built a report whose real exit_code was 1 and got
# "decision=KEEP reason=smoke-passed verification=VERIFIED", exit 0 -- a silent
# pass over a report ordering a rollback. Nested objects, duplicate keys and a
# tab character each defeated it, because `tr -d ' \n'` removes spaces and
# linefeeds and nothing else.
#
# The default of "keep" was never the bug and has not changed: rolling back
# because a parser failed means a bug in this script reverts a healthy release,
# and not measuring is not evidence of a defect. The bug was that one class of
# malformed input produced a decision that did not fail the job at all.
set -euo pipefail

REPORT="${1:?path to smoke.json required}"
# Required, not optional. The report directory holds one file per gate and they
# persist between runs, so without this a stale report from the previous deploy
# -- or a passing report from an entirely different gate -- reads as this
# deploy's verdict. An optional freshness check is one nobody passes.
NOT_BEFORE="${2:?epoch seconds recorded before the deploy required}"

unverified() { # unverified <reason> <message>
    printf 'decision=KEEP_UNVERIFIED reason=%s verification=UNVERIFIED\n' "$1"
    printf '%s\n' "$2" >&2
    exit 2
}

[ -f "$REPORT" ] || unverified no-smoke-report "the smoke gate wrote no report at $REPORT"

# A real JSON parser, not a regex over unanchored text. python3 is present
# wherever this runs; a grep that is wrong in the reassuring direction is not a
# saving worth making.
#
# It prints "<gate> <exit_code> <rules>" or nothing at all. Anything unexpected
# in the document -- a non-integer exit_code, a missing key, a nested structure
# where a scalar belongs, malformed JSON -- yields no output and is treated as
# could-not-run rather than guessed at.
parsed=$(python3 -c '
import json, sys
try:
    with open(sys.argv[1], "rb") as fh:
        d = json.load(fh)
except Exception:
    sys.exit(0)
if not isinstance(d, dict):
    sys.exit(0)
gate = d.get("gate")
code = d.get("exit_code")
# bool is a subclass of int in Python; true would otherwise read as 1 and order
# a rollback.
if not isinstance(gate, str) or isinstance(code, bool) or not isinstance(code, int):
    sys.exit(0)
rules = d.get("rules")
if not isinstance(rules, list) or not all(isinstance(r, str) for r in rules):
    rules = []
print(gate, code, ",".join(rules) or "-")
' "$REPORT" 2>/dev/null) || true

[ -n "$parsed" ] || unverified unreadable-report \
    "could not read a gate name and integer exit_code from $REPORT"

gate="${parsed%% *}"
rest="${parsed#* }"
code="${rest%% *}"
rules="${rest#* }"
[ "$rules" = "-" ] && rules="smoke-findings"

# The report must be the SMOKE gate's. It never was checked, and the report
# directory contains every gate's output side by side, so one wrong path made a
# deploy VERIFIED on the strength of the shellcheck gate.
[ "$gate" = "smoke" ] || unverified wrong-gate-report \
    "report at $REPORT is from the '$gate' gate, not smoke"

# ...and it must be from THIS deploy.
mtime=$(stat -c %Y "$REPORT" 2>/dev/null || stat -f %m "$REPORT" 2>/dev/null || echo 0)
[ "$mtime" -ge "$NOT_BEFORE" ] || unverified stale-smoke-report \
    "report at $REPORT predates this deploy (mtime $mtime < $NOT_BEFORE)"

case "$code" in
    0)
        printf 'decision=KEEP reason=smoke-passed verification=VERIFIED\n'
        exit 0
        ;;
    1)
        printf 'decision=ROLLBACK reason=%s verification=VERIFIED\n' "$rules"
        printf 'smoke found: %s\n' "$rules" >&2
        exit 1
        ;;
    2)
        unverified smoke-could-not-run \
            "the smoke gate could not run; the release stays and is recorded unverified"
        ;;
    3)
        # Nothing was deployed to smoke, yet this runs only after a deploy. That
        # contradiction means the pipeline is misconfigured -- most likely
        # SMOKE_URL is unset -- and the dangerous reading is the reassuring one,
        # because exit 3 maps to success upstream.
        unverified smoke-not-applicable-after-a-deploy \
            "smoke reported NOT APPLICABLE after a deploy: SMOKE_URL is probably unset"
        ;;
    *)
        unverified undefined-exit-code \
            "smoke reported exit $code, which its own contract does not define"
        ;;
esac
