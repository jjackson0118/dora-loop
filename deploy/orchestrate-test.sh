#!/usr/bin/env bash
# Exercise real orchestration with isolated files and replacement mechanisms.
set -euo pipefail
ROOT=$(CDPATH='' cd -- "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT
SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
count=0
run_case() {
    local name=$1 activation=$2 smoke=$3 want=$4 rollback=$5
    local work="$TMP/$name"
    mkdir -p "$work/deploy" "$work/gates/gates" "$work/app/releases/1111111"
    cp "$ROOT/deploy/remote-orchestrate.sh" "$ROOT/deploy/decide.sh" "$ROOT/deploy/prepare_event.py" \
       "$ROOT/deploy/finalize_event.py" "$ROOT/deploy/event_payload.py" "$work/deploy/"
    python3 - "$work" "$SHA" <<'FIXTURE'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]); head=sys.argv[2]; base='1'*40
(root/'event-context.json').write_text(json.dumps({'id':'fixture:run:1:attempt:1','head':head}))
(root/'history.json').write_text(json.dumps([
 {'sha':head,'authoredAt':'2026-09-06T12:00:00Z','parents':[base]},
 {'sha':base,'authoredAt':'2026-09-05T12:00:00Z','parents':[]}]))
FIXTURE
    cat > "$work/deploy/report_event.py" <<'MOCK'
import json,os,sys
from pathlib import Path
assert sys.argv[1:] == ['event.json','event-receipt.json']
event=json.loads(Path('event.json').read_text())
if os.environ['ACTIVATION']=='report-fails': raise SystemExit(1)
Path('event-receipt.json').write_text(json.dumps({'id':event['id'],'disposition':'STORED','httpStatus':201}))
MOCK
    printf jar > "$work/app.jar"
    printf old > "$work/app/releases/1111111/app.jar"
    ln -s "$work/app/releases/1111111" "$work/app/last-good"
    ln -s "$work/app/releases/1111111" "$work/app/current"
    cat > "$work/deploy/remote-release.sh" <<'MOCK'
#!/usr/bin/env bash
set -eu
[ "$3" = "$(readlink "$DORA_APP_DIR/last-good")" ]
if [ "$ACTIVATION" = pre ]; then exit 1; fi
ln -sfn "$DORA_APP_DIR/releases/$1" "$DORA_APP_DIR/current"
if [ "$ACTIVATION" = post ]; then exit 1; fi
ln -sfn "$DORA_APP_DIR/releases/$1" "$DORA_APP_DIR/last-good"
MOCK
    cat > "$work/deploy/remote-rollback.sh" <<'MOCK'
#!/usr/bin/env bash
set -eu
[ "$1" = "$DORA_APP_DIR/releases/1111111" ]
[ "$3" = "$(readlink "$DORA_APP_DIR/current")" ]
if [ "$ACTIVATION" = rollback-fails ]; then exit 9; fi
printf rollback > "$DORA_APP_DIR/rolled-back"
ln -sfn "$1" "$DORA_APP_DIR/current"
MOCK
    cat > "$work/gates/gates/smoke.sh" <<'MOCK'
#!/usr/bin/env bash
set -eu
[ "$SMOKE_EXPECT_SHA" = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ]
[ "$SMOKE_URL" = http://10.0.0.1:8080 ]
case "$SMOKE" in
    missing) exit 2 ;;
    malformed) echo broken > "$GATE_REPORT_DIR/smoke.json"; exit 2 ;;
    stale) echo '{"gate":"smoke","exit_code":0}' > "$GATE_REPORT_DIR/smoke.json"
        touch -d '2000-01-01' "$GATE_REPORT_DIR/smoke.json"; exit 0 ;;
    disagree) echo '{"gate":"smoke","exit_code":0}' > "$GATE_REPORT_DIR/smoke.json"; exit 2 ;;
    *) printf '{"gate":"smoke","exit_code":%s}' "$SMOKE" > "$GATE_REPORT_DIR/smoke.json"; exit "$SMOKE" ;;
esac
MOCK
    if [ "$name" = history-missing ]; then printf '[]' > "$work/history.json"; fi
    local checksum rc=0
    checksum=$(sha256sum "$work/app.jar" | cut -d' ' -f1)
    if [ "$name" = checksum ]; then checksum=$(printf bad | sha256sum | cut -d' ' -f1); fi
    if [ "$name" = rerun ]; then ln -sfn "$work/app/releases/$SHA" "$work/app/current"; fi
    if [ "$name" = immutable ]; then mkdir "$work/app/releases/$SHA"; echo other > "$work/app/releases/$SHA/app.jar"; fi
    (cd "$work"; DORA_APP_DIR="$work/app" ACTIVATION="$activation" SMOKE="$smoke" \
        bash deploy/remote-orchestrate.sh "$SHA" "$checksum" http://10.0.0.1:8080) > "$work/log" 2>&1 || rc=$?
    [ "$rc" -eq "$want" ] || { cat "$work/log"; echo "$name: wanted $want got $rc"; exit 1; }
    if [ "$rollback" = yes ]; then test -f "$work/app/rolled-back"; else test ! -e "$work/app/rolled-back"; fi
    if [ "$name" = disagreement ]; then
        grep -q "^decision=KEEP_UNVERIFIED " "$work/decision"
    fi
    python3 - "$work" "$name" <<'ASSERT'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]); name=sys.argv[2]
no_event={'preactivation','postactivation','checksum','rerun','immutable','rollback-failure','history-missing'}
if name in no_event:
    assert not (root/'event.json').exists(), name
else:
    event=json.loads((root/'event.json').read_text())
    outcome='ROLLED_BACK' if name=='defect' else 'SUCCESS'
    verification='VERIFIED' if name in {'good','defect','report-failure'} else 'UNVERIFIED'
    assert (event['outcome'],event['verification'])==(outcome,verification),event
    assert len(event['changes'])==1
    assert event['id']=='fixture:run:1:attempt:1'
    if name=='report-failure':
        assert not (root/'event-receipt.json').exists()
        assert (root/'app/current').readlink().name=='a'*40
    else:
        assert json.loads((root/'event-receipt.json').read_text())['id']==event['id']
ASSERT
    count=$((count + 1))
    printf 'PASS %s\n' "$name"
}
run_case good ok 0 0 no
run_case defect ok 1 1 yes
run_case unavailable ok 2 2 no
run_case not-applicable ok 3 2 no
run_case absent ok missing 2 no
run_case malformed ok malformed 2 no
run_case stale ok stale 2 no
run_case disagreement ok disagree 2 no
run_case preactivation pre 0 1 no
run_case postactivation post 0 1 yes
run_case checksum ok 0 2 no
run_case rerun ok 0 2 no
run_case immutable ok 0 2 no
run_case rollback-failure rollback-fails 1 9 no
run_case report-failure report-fails 0 2 no
run_case history-missing ok 0 2 no
printf '%s orchestration scenarios passed\n' "$count"
