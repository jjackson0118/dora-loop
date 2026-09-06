#!/usr/bin/env bash
# Upload a complete private bundle, then run it. Tailscale SSH authenticates
# the host and user; no ssh private key or application secret is needed.
set -euo pipefail
TARGET="${DEPLOY_HOST:?DEPLOY_HOST required}"
URL="${SMOKE_URL:?SMOKE_URL required}"
SHA="${GITHUB_SHA:?full commit SHA required}"
JAR="${1:?boot jar path required}"
GATES="${2:?pinned delivery-gates checkout required}"
[[ "$TARGET" =~ ^[a-zA-Z0-9.-]+$ ]] || exit 2
[[ "$URL" =~ ^http://[a-zA-Z0-9.-]+:[0-9]+$ ]] || exit 2
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || exit 2
bundle=$(mktemp -d)
trap 'rm -rf -- "$bundle"' EXIT
cp "$JAR" "$bundle/app.jar"
cp -R deploy "$bundle/deploy"
python3 deploy/event_context.py "$bundle"
mkdir "$bundle/gates"
cp -R "$GATES/gates" "$GATES/lib" "$bundle/gates/"
checksum=$(sha256sum "$bundle/app.jar" | cut -d' ' -f1)
remote=$(tailscale ssh "deploy@$TARGET" 'umask 077; mktemp -d /tmp/dora-ci.XXXXXXXX')
[[ "$remote" =~ ^/tmp/dora-ci\.[a-zA-Z0-9]+$ ]] || exit 2
# Only start after tar completed successfully: a broken upload never activates.
tar -C "$bundle" -cf - . | tailscale ssh "deploy@$TARGET" "tar -xf - -C '$remote'"
rc=0
tailscale ssh "deploy@$TARGET" "cd '$remote' && bash deploy/remote-orchestrate.sh '$SHA' '$checksum' '$URL'" || rc=$?
# Preserve evidence even on a failing decision. A transport failure retrieving
# evidence cannot turn a failed deploy green; a successful deploy becomes unknown.
mkdir -p deploy-evidence
tailscale ssh "deploy@$TARGET" "tar -cf - -C '$remote' --ignore-failed-read previous started decision reports history.json event-context.json event-draft.json event-status event.json event-receipt.json" |
    tar -xf - -C deploy-evidence || { [ "$rc" -ne 0 ] || rc=2; }
if [ "$rc" -eq 0 ]; then
    for file in previous started decision reports/smoke.json history.json event-context.json event.json event-receipt.json; do
        [ -s "deploy-evidence/$file" ] || rc=2
    done
fi
echo "Remote evidence retained at $remote"
exit "$rc"
