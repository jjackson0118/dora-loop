#!/usr/bin/env bash
# Return the service to a previous release. Runs ON the deploy target.
#
#   bash -s -- <previous-release-dir> [--force]
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
FORCE="${2:-}"

APP_DIR=/opt/dora-loop
LOCK="$APP_DIR/.deploy.lock"
SETTLE=60

say() { printf '   %s\n' "$1"; }
die() { printf 'ROLLBACK FAILED: %s\n' "$1" >&2; exit 1; }

[ -d "$PREVIOUS" ]           || die "no such release: $PREVIOUS"
[ -f "$PREVIOUS/app.jar" ]   || die "$PREVIOUS has no app.jar"

# Wait, where a deploy fails fast. A rollback that arrives while a deploy is
# thirty seconds from finishing should queue behind it rather than fight it;
# the deploy is the deferrable operation, the rollback is not.
#
# It does NOT preempt automatically. Breaking a flock means killing whoever
# holds it, which re-creates the two-writer condition this is here to remove.
# --force proceeds without the lock and says so loudly: emergency authority is
# a decision a person makes, not a default a script takes.
exec 9>"$LOCK"
if ! flock -w 90 9; then
    holder=$(cat "$LOCK" 2>/dev/null || echo 'holder unknown')
    if [ "$FORCE" = "--force" ]; then
        printf 'WARNING: proceeding WITHOUT the lock; a deploy may be in flight: %s\n' "$holder" >&2
    else
        die "a deploy holds the lock after 90s: $holder (pass --force to override, deliberately)"
    fi
else
    printf 'rollback to %s by %s since %s\n' "$(basename "$PREVIOUS")" "$(id -un)" "$(date -Is)" >&9
fi

FROM=$(readlink "$APP_DIR/current" 2>/dev/null || echo '<none>')
# Optional orchestration ownership guard, evaluated while holding the lock.
[ -z "${3:-}" ] || [ "$FROM" = "$3" ] || die "current moved; refusing to withdraw a peer release"
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

# If the release being rolled away from tripped the start limit, the unit is in
# `failed` and `systemctl restart` answers "start request repeated too quickly".
# Clearing the counter first is the difference between a rollback that works on
# a host that is already down and one that does not.
sudo -n systemctl reset-failed dora-loop 2>/dev/null || true
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

# The release we just restored is, by demonstration, good: it serves. Advancing
# last-good here means a subsequent deploy rolls back to this rather than to the
# release that was withdrawn.
ln -sfn "$PREVIOUS" "$APP_DIR/.last-good.$$"
mv -Tf "$APP_DIR/.last-good.$$" "$APP_DIR/last-good"

printf 'ROLLBACK OK now serving %s (was %s)\n' "$RELEASE_ID" "$FROM"
