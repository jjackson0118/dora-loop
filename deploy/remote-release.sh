#!/usr/bin/env bash
# Activate a release. Runs ON the deploy target, streamed in over stdin.
#
#   bash -s -- <release-id> <expected-sha256> [last-good-observed-at-start]
#
# A separate file rather than a string built by the caller: it is covered by
# the shellcheck gate like everything else, and it can be run directly against
# a target during development, so the logic is testable independently of the
# transport.
#
# It takes no secrets. Operator bootstrap or token rotation populates app.env
# on the target; releases preserve it and verify the ingest token is present.
#
# WHAT IT DELIBERATELY DOES NOT DO: revert on failure. An earlier design used a
# bash trap to re-point the symlink if anything after the flip went wrong. Three
# arguments against it, and they are the reason this script now fails and stops:
#
#   - The trap does not survive the failures that matter. Bash runs EXIT traps
#     on normal exit and trapped signals, never on SIGKILL, and this runs on an
#     ephemeral CI node that is torn down when a job is cancelled. Most of the
#     wall clock is spent in the readiness sleep loop, which is exactly where a
#     human hits Cancel.
#   - A revert is not a revert until a restart succeeds. If the trap's own
#     restart fails, the symlink says one release and the process is running
#     another, which is harder to diagnose than a clean stop.
#   - It puts rollback POLICY inside the deploy mechanism. decide.sh exists
#     precisely because keep-or-roll-back is a decision made deliberately from
#     evidence, and it refuses to roll back on "could not measure". A trap that
#     reverts on any post-flip failure -- including a 3-second curl blip --
#     contradicts that, from a place nobody can observe or override.
#
# So the caller reverts, as an explicit step, using last-good.
set -euo pipefail

RELEASE_ID="${1:?release id required}"
EXPECT_SHA256="${2:?expected jar sha256 required}"
# The value the caller read from last-good BEFORE this deploy. Optional so the
# script still runs by hand, but the caller should pass it: it turns the flip
# into a compare-and-swap and catches a peer that deployed underneath us.
LAST_GOOD_AT_START="${3:-}"

APP_DIR=/opt/dora-loop
ETC_DIR=/etc/dora-loop
RELEASE_DIR="$APP_DIR/releases/$RELEASE_ID"
LOCK="$APP_DIR/.deploy.lock"
SETTLE=60

say()  { printf '   %s\n' "$1"; }
die()  { printf 'DEPLOY FAILED: %s\n' "$1" >&2; exit 1; }

# --- preconditions ---------------------------------------------------------
[ -f "$RELEASE_DIR/app.jar" ] || die "no jar at $RELEASE_DIR/app.jar"

# The transfer is a pipe over a network. A truncated jar exists, has plausible
# size, and fails at class-load time minutes later, looking like a code problem.
actual=$(sha256sum "$RELEASE_DIR/app.jar" | cut -d' ' -f1)
[ "$actual" = "$EXPECT_SHA256" ] || die "jar checksum mismatch: expected $EXPECT_SHA256, got $actual"
say "jar verified ($actual)"

# app.env must already contain the operator-provisioned token. If it is absent,
# the service starts and refuses every write with 503 while readiness reports
# 200 -- which no probe in this script would otherwise catch.
#
# It no longer checks a build identity here, because there is no longer one to
# check: the sha is stamped into the artifact and read from
# META-INF/build-info.properties. That is what makes the read-back below
# meaningful rather than circular.
grep -q '^DORA_INGEST_TOKEN=..*' "$ETC_DIR/app.env" \
    || die "app.env has no ingest token; the service would refuse writes with 503"
say "app.env carries an ingest token"

# --- serialise the part that is not atomic ---------------------------------
# flock, because it is a property of the open file description: the kernel
# releases it when the last descriptor closes, including on SIGKILL and on an
# SSH session torn down with the runner. There is no stale lock to garbage
# collect and no timeout to tune.
#
# Fail fast rather than wait. A deploy is the deferrable operation, and a deploy
# that queues behind a rollback is the worst outcome available: it lands thirty
# seconds later and silently undoes the emergency action.
exec 9>"$LOCK"
if ! flock -n 9; then
    die "another deploy or rollback holds the lock: $(cat "$LOCK" 2>/dev/null || echo 'holder unknown')"
fi
# Identity, so a future contender gets a sentence rather than "could not acquire
# lock" -- which is the same failure this project keeps objecting to: a message
# that sends someone after the wrong subsystem.
printf 'deploy %s by %s since %s\n' "$RELEASE_ID" "$(id -un)" "$(date -Is)" >&9

# --- compare-and-swap against a marker a failed deploy cannot write ---------
LAST_GOOD=$(readlink "$APP_DIR/last-good" 2>/dev/null || true)
if [ -n "$LAST_GOOD_AT_START" ] && [ "$LAST_GOOD" != "$LAST_GOOD_AT_START" ]; then
    die "last-good moved from $LAST_GOOD_AT_START to ${LAST_GOOD:-<none>} while this deploy was preparing"
fi
say "rollback target: ${LAST_GOOD:-<none, first deploy>}"

# --- activate --------------------------------------------------------------
# rename(2), not unlink-then-symlink. `ln -sfn` over an existing symlink leaves
# a window in which `current` does not exist, and Restart=on-failure can fire
# into it.
ln -sfn "$RELEASE_DIR" "$APP_DIR/.current.$$"
mv -Tf "$APP_DIR/.current.$$" "$APP_DIR/current"

before_invocation=$(systemctl show dora-loop --property=InvocationID --value 2>/dev/null || true)
sudo -n systemctl restart dora-loop
say "restarted"

# --- wait for it to actually serve -----------------------------------------
waited=0
until curl -fsS -m 3 -o /dev/null http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null; do
    waited=$(( waited + 2 ))
    [ "$waited" -lt "$SETTLE" ] || die "not ready ${SETTLE}s after restart"
    sleep 2
done
say "ready after ${waited} polls"

# --- read back: the running service must agree with what we deployed -------
# Trusting the exit codes above is the practice this project argues against.
# `sudo systemctl restart` returns success for a jar that is a text file,
# because Type=exec only requires execve to succeed.
live_link=$(readlink "$APP_DIR/current")
[ "$live_link" = "$RELEASE_DIR" ] || die "current points at $live_link, not $RELEASE_DIR"

# A crash loop can satisfy a readiness poll: the service comes up, answers, and
# dies, repeatedly. InvocationID changes on every start, so a value that has
# moved again since the restart means the process answering is not the one the
# restart produced.
after_invocation=$(systemctl show dora-loop --property=InvocationID --value 2>/dev/null || true)
[ -n "$after_invocation" ] && [ "$after_invocation" != "$before_invocation" ] \
    || die "the unit did not start a new invocation; it is not running this release"
state=$(systemctl show dora-loop --property=ActiveState --value 2>/dev/null || true)
[ "$state" = "active" ] || die "unit is $state, not active"

info=$(curl -fsS -m 3 http://127.0.0.1:8080/actuator/info 2>/dev/null || true)
case "$info" in
    *"$RELEASE_ID"*) say "serving $RELEASE_ID" ;;
    *) die "activated $RELEASE_ID but the service reports: ${info:-<no answer>}" ;;
esac

settled=$(systemctl show dora-loop --property=InvocationID --value 2>/dev/null || true)
[ "$settled" = "$after_invocation" ] \
    || die "the unit restarted again during verification; it is crash-looping"

# --- only now is this release good -----------------------------------------
# The LAST statement, deliberately. last-good is the one thing a half-finished
# deploy must never write, because it is what the caller rolls back to. Nothing
# else in this script touches it, so it is monotone in verified releases and
# a killed run leaves it exactly as it was found.
ln -sfn "$RELEASE_DIR" "$APP_DIR/.last-good.$$"
mv -Tf "$APP_DIR/.last-good.$$" "$APP_DIR/last-good"

printf 'DEPLOY OK %s (previous %s)\n' "$RELEASE_ID" "${LAST_GOOD:-none}"
