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

# The build identity is stamped in app.env, which the failed deploy rewrote.
# Left alone, the rolled-back service would report the release we just backed
# away from -- an operator checking whether the rollback worked would be told
# it had not. Restoring it here keeps the stamp and the running code in
# agreement, which is the property the deploy script also verifies.
RELEASE_ID=$(basename "$PREVIOUS")
sed_free_rewrite() {
    # No `sed -i`: it writes a temp file in the directory and renames, and the
    # deploy account deliberately cannot create files in /etc/dora-loop. This
    # truncates the existing inode instead, which needs only write on the file.
    local tmp
    tmp=$(grep -v '^DORA_BUILD_SHA=' /etc/dora-loop/app.env || true)
    { printf '%s\n' "$tmp"; printf 'DORA_BUILD_SHA=%s\n' "$RELEASE_ID"; } \
        > /etc/dora-loop/app.env
}
sed_free_rewrite
say "build identity restored to $RELEASE_ID"

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
