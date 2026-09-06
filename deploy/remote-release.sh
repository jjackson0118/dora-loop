#!/usr/bin/env bash
# Activate a release. Runs ON the deploy target, streamed in over stdin.
#
#   bash -s -- <release-id> <expected-sha256>
#
# A separate file rather than a string built by the caller, for two reasons: it
# is covered by the shellcheck gate like everything else, and it can be run
# directly against the target during development without going through ssh --
# so the logic is testable independently of the transport.
#
# It takes no secrets. The ingest token is written to app.env by a prior step
# that streams it over stdin, and this script's job is to VERIFY that step
# landed before it activates anything. That check is the point: a deploy that
# half-applied and reported success is what motivated this whole script.
set -euo pipefail

RELEASE_ID="${1:?release id required}"
EXPECT_SHA256="${2:?expected jar sha256 required}"

APP_DIR=/opt/dora-loop
ETC_DIR=/etc/dora-loop
RELEASE_DIR="$APP_DIR/releases/$RELEASE_ID"
SETTLE=60

say()  { printf '   %s\n' "$1"; }
die()  { printf 'DEPLOY FAILED: %s\n' "$1" >&2; exit 1; }

# --- preconditions ---------------------------------------------------------
[ -f "$RELEASE_DIR/app.jar" ] || die "no jar at $RELEASE_DIR/app.jar"

# The transfer is a pipe over a network. A truncated jar is a file that exists,
# has plausible size, and fails at class-load time -- which surfaces as a
# service that will not start, minutes later, looking like a code problem.
actual=$(sha256sum "$RELEASE_DIR/app.jar" | cut -d' ' -f1)
[ "$actual" = "$EXPECT_SHA256" ] || die "jar checksum mismatch: expected $EXPECT_SHA256, got $actual"
say "jar verified ($actual)"

# Verify the PREVIOUS step landed. app.env is written by the caller streaming
# the token over stdin; if that failed the service would start and refuse every
# write with 503 while readiness reported 200, which no probe here would catch.
#
# It no longer checks a build identity in app.env, because there is no longer
# one to check: the sha is stamped into the artifact by Gradle and read from
# META-INF/build-info.properties. That is what makes the read-back below
# meaningful rather than circular -- previously /actuator/info echoed a variable
# this script had verified moments earlier, so a release directory could be
# named anything at all and the deploy would confirm it.
grep -q '^DORA_INGEST_TOKEN=..*' "$ETC_DIR/app.env" \
    || die "app.env has no ingest token; the service would refuse writes with 503"
say "app.env carries an ingest token"

# --- the rollback point, recorded before anything changes ------------------
PREVIOUS=$(readlink "$APP_DIR/current" 2>/dev/null || true)
say "previous release: ${PREVIOUS:-<none>}"

# --- activate --------------------------------------------------------------
ln -sfn "$RELEASE_DIR" "$APP_DIR/current"
sudo -n systemctl restart dora-loop
say "restarted"

# --- wait for it to actually serve -----------------------------------------
waited=0
until curl -fsS -m 3 -o /dev/null http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null; do
    waited=$(( waited + 2 ))
    [ "$waited" -lt "$SETTLE" ] || die "not ready ${SETTLE}s after restart"
    sleep 2
done
say "ready after ${waited}s"

# --- read back: the running service must agree with what we deployed -------
# Trusting the exit codes above is the practice this project argues against.
# ln reports success for a link nobody follows; systemctl reports success for a
# unit that starts and then serves the wrong thing.
live_link=$(readlink "$APP_DIR/current")
[ "$live_link" = "$RELEASE_DIR" ] || die "current points at $live_link, not $RELEASE_DIR"

info=$(curl -fsS -m 3 http://127.0.0.1:8080/actuator/info 2>/dev/null || true)
case "$info" in
    *"$RELEASE_ID"*) say "serving $RELEASE_ID" ;;
    *) die "activated $RELEASE_ID but the service reports: ${info:-<no answer>}" ;;
esac

printf 'DEPLOY OK %s (previous %s)\n' "$RELEASE_ID" "${PREVIOUS:-none}"
