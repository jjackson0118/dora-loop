#!/usr/bin/env bash
# Decide what to do after the smoke gate, by reading its report.
#
#   decide.sh <path to smoke.json>
#
# It reads the gate's own JSON report rather than the CI step's status, because
# ci/run-gate.sh maps BOTH exit 1 and exit 2 to failure for the orchestrator --
# an orchestrator has two states and the contract has four. By the time a
# workflow step has a status, the distinction this decision turns on is gone.
#
# Exits mirror the gate contract deliberately:
#   0  keep the release; smoke verified it
#   1  ROLL BACK; smoke found a defect in what was deployed
#   2  keep the release, UNVERIFIED; smoke could not find out
#
# The exit-2 case is the one worth arguing about, so the argument is here.
# Rolling back automatically on "could not measure" means a missing curl, a
# DNS blip, or an expired certificate reverts a healthy release -- an
# automated outage caused by the machinery that exists to prevent outages. And
# "could not measure" is not evidence of a defect, so treating it as one would
# report a change failure that never happened, corrupting the very metric this
# project computes.
#
# So exit 2 keeps the release, marks the deployment UNVERIFIED, and fails the
# job loudly for a human. That is the honest position: something is deployed,
# nobody checked it, and the record says so rather than guessing in either
# direction.
set -euo pipefail

REPORT="${1:?path to smoke.json required}"

[ -f "$REPORT" ] || {
    # A missing report is itself a could-not-run. Notably it is NOT a pass:
    # a gate that produced no evidence has told us nothing, and the default
    # when told nothing must never be "ship it, it is fine".
    printf 'decision=KEEP_UNVERIFIED reason=no-smoke-report verification=UNVERIFIED\n'
    printf 'the smoke gate wrote no report at %s\n' "$REPORT" >&2
    exit 2
}

code=$(tr -d ' \n' < "$REPORT" | grep -oE '"exit_code":[0-9]+' | grep -oE '[0-9]+$' || true)
rules=$(tr -d ' \n' < "$REPORT" | grep -oE '"rules":\[[^]]*\]' | tr -d '"[]' | sed 's/rules://' || true)

case "${code:-}" in
    0)
        printf 'decision=KEEP reason=smoke-passed verification=VERIFIED\n'
        exit 0
        ;;
    1)
        printf 'decision=ROLLBACK reason=%s verification=VERIFIED\n' "${rules:-smoke-findings}"
        printf 'smoke found: %s\n' "${rules:-unspecified}" >&2
        exit 1
        ;;
    2)
        printf 'decision=KEEP_UNVERIFIED reason=smoke-could-not-run verification=UNVERIFIED\n'
        printf 'the smoke gate could not run; the release stays and is recorded unverified\n' >&2
        exit 2
        ;;
    3)
        # Nothing was deployed to smoke, yet this script only runs after a
        # deploy. That contradiction means the pipeline is misconfigured --
        # most likely SMOKE_URL was never set -- and the dangerous reading is
        # the reassuring one, because exit 3 maps to success upstream.
        printf 'decision=KEEP_UNVERIFIED reason=smoke-not-applicable-after-a-deploy verification=UNVERIFIED\n'
        printf 'smoke reported NOT APPLICABLE after a deploy: SMOKE_URL is probably unset\n' >&2
        exit 2
        ;;
    *)
        printf 'decision=KEEP_UNVERIFIED reason=unreadable-report verification=UNVERIFIED\n'
        printf 'could not read an exit_code from %s\n' "$REPORT" >&2
        exit 2
        ;;
esac
