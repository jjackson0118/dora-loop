#!/usr/bin/env bash
# Return the service to a previous release. Runs ON the deploy target.
#
#   bash -s -- <previous-release-dir>
#
# The previous release is passed in rather than discovered, because it was
# recorded BEFORE the deploy that is now being undone. Discovering it here
# would mean asking a system that is currently in the state we are trying to
# leave -- and during an incident that is the least reliable moment to start
# working out where to go back to.
#
# It re-points a symlink and restarts. It does not fetch, rebuild, or reinstall
# anything, so it works when the network is down, when the registry is down,
# and when the build that produced the current release is no longer
# reproducible. That is the whole reason releases are kept side by side.
set -euo pipefail

PREVIOUS="${1:?previous release directory required}"

APP_DIR=/opt/dora-loop
SETTLE=60

say() { printf '   %s\n' "$1"; }
die() { printf 'ROLLBACK FAILED: %s\n' "$1" >&2; exit 1; }

[ -d "$PREVIOUS" ]           || die "no such release: $PREVIOUS"
[ -f "$PREVIOUS/app.jar" ]   || die "$PREVIOUS has no app.jar"

FROM=$(readlink "$APP_DIR/current" 2>/dev/null || echo '<none>')
say "rolling back from $FROM to $PREVIOUS"

ln -sfn "$PREVIOUS" "$APP_DIR/current"

# app.env is deliberately NOT touched.
#
# An earlier version rewrote it here to restore the build identity, because the
# identity used to live in DORA_BUILD_SHA. It does not any more -- it is stamped
# into the artifact -- so re-pointing the symlink is sufficient and the rewrite
# is gone.
#
# That deletion removes a real hazard rather than tidying. The rewrite read the
# file, filtered a line and truncated with `>`; a reviewer killed it mid-write
# and left app.env at zero bytes, destroying the only copy of the ingest token
# on the host, which this account cannot recreate because it may not create
# files in /etc/dora-loop. Worse, a rollback over a tokenless app.env passed
# every one of this script's checks -- readiness 200, correct identity,
# ROLLBACK OK -- while every ingest write answered 503. The safest version of
# that code is none of it.
RELEASE_ID=$(basename "$PREVIOUS")

sudo -n systemctl restart dora-loop
say "restarted"

waited=0
until curl -fsS -m 3 -o /dev/null http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null; do
    waited=$(( waited + 2 ))
    [ "$waited" -lt "$SETTLE" ] || die "not ready ${SETTLE}s after rollback -- the previous release does not start either"
    sleep 2
done
say "ready after ${waited}s"

live=$(readlink "$APP_DIR/current")
[ "$live" = "$PREVIOUS" ] || die "current points at $live, not $PREVIOUS"

info=$(curl -fsS -m 3 http://127.0.0.1:8080/actuator/info 2>/dev/null || true)
case "$info" in
    *"$RELEASE_ID"*) say "serving $RELEASE_ID" ;;
    *) die "rolled back to $RELEASE_ID but the service reports: ${info:-<no answer>}" ;;
esac

printf 'ROLLBACK OK now serving %s (was %s)\n' "$RELEASE_ID" "$FROM"
