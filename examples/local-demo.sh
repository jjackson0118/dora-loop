#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after ./gradlew build. Requires Bash, Docker,
# Java 21, Python 3, curl, OpenSSL and standard Linux command-line tools.
umask 077
export PORT=${PORT:-18080}
python3 - <<'PORTCHECK'
import os, socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', int(os.environ['PORT'])))
PORTCHECK
scratch=$(mktemp -d)
db="dora-quickstart-$(date +%s)-$$"
app_pid=
cleanup() {
  if [ -n "$app_pid" ]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  docker rm -f -v "$db" >/dev/null 2>&1 || true
  rm -rf -- "$scratch"
}
trap cleanup EXIT
POSTGRES_PASSWORD=$(openssl rand -hex 24)
export POSTGRES_PASSWORD
DORA_INGEST_TOKEN=$(openssl rand -hex 32)
export DORA_INGEST_TOKEN
docker run --detach --name "$db" --publish 127.0.0.1::5432 \
  --env POSTGRES_PASSWORD --env POSTGRES_USER=doraloop --env POSTGRES_DB=doraloop \
  postgres:16-alpine >/dev/null
for _attempt in {1..60}; do
  if docker exec "$db" pg_isready -U doraloop -d doraloop >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$db" pg_isready -U doraloop -d doraloop
db_port=$(docker port "$db" 5432/tcp | awk -F: '{print $NF}')
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$db_port/doraloop"
export SPRING_DATASOURCE_USERNAME=doraloop
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
jars=()
for jar in api/build/libs/*.jar; do [[ "$jar" == *-plain.jar ]] || jars+=("$jar"); done
[[ ${#jars[@]} -eq 1 ]]
java -jar "${jars[0]}" --server.address=127.0.0.1 > "$scratch/app.log" 2>&1 &
app_pid=$!
for _attempt in {1..60}; do
  if curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent "http://127.0.0.1:$PORT/actuator/health/readiness" > "$scratch/health.json"; then break; fi
  kill -0 "$app_pid"
  sleep 1
done
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent "http://127.0.0.1:$PORT/actuator/health/readiness"
printf "\n"
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent "http://127.0.0.1:$PORT/api/v1/services/quickstart/report" > "$scratch/before.json"
python3 - "$scratch" <<'PY'
import json, sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
root=Path(sys.argv[1]); now=datetime.now(timezone.utc)
event={"id":"quickstart-deployment-1","service":"quickstart","environment":"production",
       "deployedAt":(now-timedelta(minutes=1)).isoformat(),"outcome":"SUCCESS","verification":"VERIFIED",
       "changes":[{"commitSha":"a"*40,"authoredAt":(now-timedelta(hours=1)).isoformat()}]}
(root/"event.json").write_text(json.dumps(event))
PY
printf 'X-Dora-Ingest-Token: %s\n' "$DORA_INGEST_TOKEN" > "$scratch/auth-header"
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail-with-body --silent --show-error --write-out '\nHTTP %{http_code}\n' \
  --header @"$scratch/auth-header" --header 'Content-Type: application/json' \
  --data-binary @"$scratch/event.json" "http://127.0.0.1:$PORT/api/v1/deployments"
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail-with-body --silent --show-error --write-out '\nHTTP %{http_code}\n' \
  --header @"$scratch/auth-header" --header 'Content-Type: application/json' \
  --data-binary @"$scratch/event.json" "http://127.0.0.1:$PORT/api/v1/deployments"
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent "http://127.0.0.1:$PORT/api/v1/services/quickstart/report" > "$scratch/after.json"
python3 - "$scratch" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
before=json.loads((root/'before.json').read_text())
after=json.loads((root/'after.json').read_text())
assert all(m['state']=='UNOBSERVED' and m['value'] is None and m['observedN']==0
           for m in before['metrics']+before['dataQuality'])
metrics={m['name']:m for m in after['metrics']+after['dataQuality']}
assert metrics['deployment_frequency']['observedN']==1
assert metrics['data_quality.unverified_deployments']['state']=='OK'
assert metrics['data_quality.unverified_deployments']['value']==0
assert metrics['time_to_restore']['state']=='UNOBSERVED'
print('Confirmed: empty report is UNOBSERVED; one deployment after replay; verification OK; no invented recovery.')
print(json.dumps(after,indent=2))
PY
