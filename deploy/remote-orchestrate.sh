#!/usr/bin/env bash
# Runs in an isolated uploaded bundle ON the target. No credential is uploaded.
set -euo pipefail
RELEASE_ID="${1:?release SHA required}"
EXPECT_SHA256="${2:?artifact checksum required}"
export SMOKE_URL="${3:?consumer listener URL required}"
APP_DIR="${DORA_APP_DIR:-/opt/dora-loop}"
[[ "$RELEASE_ID" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "$EXPECT_SHA256" =~ ^[0-9a-f]{64}$ ]] || exit 2
RELEASE_DIR="$APP_DIR/releases/$RELEASE_ID"
exec 8>"$APP_DIR/.orchestrate.lock"
flock -n 8 || { echo 'another CI orchestration is active' >&2; exit 2; }
# A retry must not mutate the currently serving jar, nor claim ownership of it.
[ "$(readlink "$APP_DIR/current" || true)" != "$RELEASE_DIR" ] || {
    echo 'candidate is already current; inspect previous run before retrying' >&2; exit 2;
}
PREVIOUS=$(readlink "$APP_DIR/last-good" || true)
[ -n "$PREVIOUS" ] || { echo 'bootstrap requires a manually verified last-good' >&2; exit 2; }
[[ "$PREVIOUS" =~ ^$APP_DIR/releases/[0-9a-f]+$ ]] || exit 2
printf '%s\n' "$PREVIOUS" > previous
START=$(date +%s)
printf '%s\n' "$START" > started
[ "$(sha256sum app.jar | cut -d' ' -f1)" = "$EXPECT_SHA256" ] || exit 2
# Fully receive and checksum before publishing. A same-SHA rebuild differing in
# bytes is rejected rather than overwriting a release used by rollback.
if [ -e "$RELEASE_DIR" ]; then
    [ "$(sha256sum "$RELEASE_DIR/app.jar" | cut -d' ' -f1)" = "$EXPECT_SHA256" ] || {
        echo 'immutable release already exists with different bytes' >&2; exit 2;
    }
else
    staged=$(mktemp -d "$APP_DIR/releases/.stage.XXXXXXXX")
    cp app.jar "$staged/app.jar"
    chmod 755 "$staged"
    chmod 644 "$staged/app.jar"
    mv -T "$staged" "$RELEASE_DIR"
fi
rollback() {
    # Guard checked again under rollback's lock: never undo a peer's release.
    bash deploy/remote-rollback.sh "$PREVIOUS" "" "$RELEASE_DIR"
}
activation=0
bash deploy/remote-release.sh "$RELEASE_ID" "$EXPECT_SHA256" "$PREVIOUS" || activation=$?
if [ "$activation" -ne 0 ]; then
    echo 'activation failed; recovering only if candidate owns current' >&2
    if [ "$(readlink "$APP_DIR/current" || true)" = "$RELEASE_DIR" ]; then rollback; fi
    exit 1
fi
export GATE_REPORT_DIR="$PWD/reports" SMOKE_EXPECT_SHA="$RELEASE_ID"
mkdir reports
smoke=0
bash gates/gates/smoke.sh || smoke=$?
decision=0
bash deploy/decide.sh "$GATE_REPORT_DIR/smoke.json" "$START" > decision || decision=$?
# A process/report disagreement is absent trustworthy evidence, never green.
if [ "$smoke" -ne "$decision" ]; then
    echo 'decision=KEEP_UNVERIFIED reason=smoke-process-report-disagreement verification=UNVERIFIED' > decision
    cat decision
    exit 2
fi
cat decision
case "$decision" in
    0) exit 0 ;;
    1) rollback; exit 1 ;;
    *) exit 2 ;;
esac
