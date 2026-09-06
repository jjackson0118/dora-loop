#!/usr/bin/env bash
# The corpus decide.sh must survive.
#
# Every case below except the first two is a report that a previous version
# read WRONGLY, and every one of those misreads resolved to keeping the
# release. One produced a silent pass -- exit 0, "verification=VERIFIED" -- over
# a report whose real exit_code was 1.
#
# This runs in CI rather than by hand. A proof nothing executes is evidence
# about the day it was written, not a control over what happens next.
set -uo pipefail

HERE="$(CDPATH='' cd -P -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DECIDE="$HERE/decide.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

pass=0 fail=0
NOT_BEFORE=$(( $(date +%s) - 60 ))

check() { # check <name> <expected-exit> <expect-decision-substring> <json>
    local name="$1" want="$2" wantdec="$3" json="$4"
    printf '%s' "$json" > "$WORK/smoke.json"
    local out rc
    out=$("$DECIDE" "$WORK/smoke.json" "$NOT_BEFORE" 2>/dev/null); rc=$?
    if [ "$rc" = "$want" ] && printf '%s' "$out" | grep -qF "$wantdec"; then
        printf '  ok    %-34s exit=%s %s\n' "$name" "$rc" "$out"
        pass=$(( pass + 1 ))
    else
        printf '  FAIL  %-34s exit=%s (want %s) got: %s\n' "$name" "$rc" "$want" "${out:-<none>}"
        fail=$(( fail + 1 ))
    fi
}

echo "-- the only two shapes that may keep a release without failing the job --"
check "clean pass"            0 "decision=KEEP reason=smoke-passed" \
    '{"gate":"smoke","exit_code":0,"rules":[],"scanned":3}'
check "honest finding"        1 "decision=ROLLBACK" \
    '{"gate":"smoke","exit_code":1,"rules":["wrong-build-serving"],"scanned":3}'

echo
echo "-- reports a grep-based parser read wrongly, all of which kept the release --"
check "nested exit_code"      1 "decision=ROLLBACK" \
    '{"gate":"smoke","exit_code":1,"rules":["not-ready"],"checks":[{"name":"a","exit_code":0}]}'
check "duplicate key"         1 "decision=ROLLBACK" \
    '{"gate":"smoke","exit_code":0,"rules":["not-ready"],"exit_code":1}'
check "tab indented"          1 "decision=ROLLBACK" \
    '{"gate":"smoke",
	"exit_code":	1,	"rules":["not-ready"]}'
check "tab plus nested zero"  1 "decision=ROLLBACK" \
    '{"gate":"smoke",
	"exit_code":	1,	"rules":["not-ready"],	"sub":{"exit_code":0}}'
check "exit_code inside text" 1 "decision=ROLLBACK" \
    '{"gate":"smoke","exit_code":1,"rules":["not-ready"],"note":"saw \"exit_code\":0 in the body"}'

echo
echo "-- reports that are not this gate, or not this deploy --"
check "another gate entirely" 2 "reason=wrong-gate-report" \
    '{"gate":"shellcheck","exit_code":0,"rules":[],"scanned":34}'

printf '%s' '{"gate":"smoke","exit_code":0,"rules":[],"scanned":3}' > "$WORK/smoke.json"
touch -d '2020-01-01' "$WORK/smoke.json" 2>/dev/null || touch -t 202001010000 "$WORK/smoke.json"
out=$("$DECIDE" "$WORK/smoke.json" "$NOT_BEFORE" 2>/dev/null); rc=$?
if [ "$rc" = 2 ] && printf '%s' "$out" | grep -qF 'reason=stale-smoke-report'; then
    printf '  ok    %-34s exit=%s %s\n' "stale report from a prior deploy" "$rc" "$out"; pass=$(( pass + 1 ))
else
    printf '  FAIL  %-34s exit=%s got: %s\n' "stale report from a prior deploy" "$rc" "${out:-<none>}"; fail=$(( fail + 1 ))
fi

echo
echo "-- malformed, missing, or outside the contract: never a pass --"
check "not an object"         2 "reason=unreadable-report" '["smoke",0]'
check "truncated json"        2 "reason=unreadable-report" '{"gate":"smoke","exit_code":'
check "empty file"            2 "reason=unreadable-report" ''
check "exit_code as string"   2 "reason=unreadable-report" \
    '{"gate":"smoke","exit_code":"0","rules":[]}'
check "exit_code true"        2 "reason=unreadable-report" \
    '{"gate":"smoke","exit_code":true,"rules":[]}'
check "exit_code missing"     2 "reason=unreadable-report" \
    '{"gate":"smoke","rules":[]}'
check "undefined exit 7"      2 "reason=undefined-exit-code" \
    '{"gate":"smoke","exit_code":7,"rules":[]}'
check "could not run"         2 "reason=smoke-could-not-run" \
    '{"gate":"smoke","exit_code":2,"rules":[],"scanned":0}'
check "not applicable"        2 "reason=smoke-not-applicable" \
    '{"gate":"smoke","exit_code":3,"rules":[]}'

rm -f "$WORK/smoke.json"
out=$("$DECIDE" "$WORK/smoke.json" "$NOT_BEFORE" 2>/dev/null); rc=$?
if [ "$rc" = 2 ] && printf '%s' "$out" | grep -qF 'reason=no-smoke-report'; then
    printf '  ok    %-34s exit=%s %s\n' "no report at all" "$rc" "$out"; pass=$(( pass + 1 ))
else
    printf '  FAIL  %-34s exit=%s got: %s\n' "no report at all" "$rc" "${out:-<none>}"; fail=$(( fail + 1 ))
fi

echo
# A sweep that examined nothing is not a pass, here as everywhere else.
if [ "$(( pass + fail ))" -lt 18 ]; then
    printf 'ERROR: only %s cases ran; the corpus did not execute\n' "$(( pass + fail ))" >&2
    exit 2
fi
printf '%s passed, %s failed, over %s cases\n' "$pass" "$fail" "$(( pass + fail ))"
[ "$fail" -eq 0 ]
