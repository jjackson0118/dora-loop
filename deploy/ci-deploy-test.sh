#!/usr/bin/env bash
# Exercise the real CI transport adapter with a fake Tailscale executable.
# No network or deploy target is used; every file lives under a temporary root.
set -euo pipefail
SCRIPT_DIR="$(CDPATH='' cd -P -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 - "$SCRIPT_DIR/ci-deploy.sh" <<'PY'
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile

adapter = Path(sys.argv[1]).resolve()
fake = r'''#!/usr/bin/env python3
import io, os, sys, tarfile
from pathlib import Path
command = sys.argv[-1]
case = os.environ['CASE']
with open(os.environ['CALL_LOG'], 'a') as f:
    f.write(command + '\n')
if 'mktemp' in command:
    print('/tmp/dora-ci.TEST')
elif 'tar -xf' in command:
    sys.stdin.buffer.read()
    if case == 'upload-failed': sys.exit(1)
elif 'remote-orchestrate.sh' in command:
    sys.exit({'remote-failed': 1, 'remote-unverified': 2, 'ssh-lost': 255}.get(case, 0))
elif 'tar -cf' in command:
    if case == 'evidence-transport-failed': sys.exit(255)
    files = {'previous': '/opt/dora-loop/releases/abc\n', 'started': '12345\n',
             'decision': 'decision=KEEP verification=VERIFIED\n',
             'reports/smoke.json': '{"gate":"smoke","exit_code":0}\n'}
    if case == 'empty-evidence': files = {}
    if case == 'missing-report': del files['reports/smoke.json']
    if case == 'empty-decision': files['decision'] = ''
    with tarfile.open(fileobj=sys.stdout.buffer, mode='w|') as tar:
        for name, text in files.items():
            data = text.encode()
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            tar.addfile(entry, io.BytesIO(data))
else:
    sys.exit('unexpected transport command: ' + command)
'''
cases = {'success': 0, 'upload-failed': 1, 'remote-failed': 1,
         'remote-unverified': 2, 'ssh-lost': 255, 'evidence-transport-failed': 2,
         'empty-evidence': 2, 'missing-report': 2, 'empty-decision': 2}
with tempfile.TemporaryDirectory(prefix='dora-transport-test-') as directory:
    root = Path(directory)
    for case, expected in cases.items():
        work = root / case
        (work / 'bin').mkdir(parents=True)
        (work / 'deploy').mkdir()
        (work / 'gates/gates').mkdir(parents=True)
        (work / 'gates/lib').mkdir()
        (work / 'app.jar').write_bytes(b'fixture artifact')
        executable = work / 'bin/tailscale'
        executable.write_text(fake)
        executable.chmod(0o755)
        log = work / 'calls'
        env = dict(os.environ, PATH=str(work/'bin')+os.pathsep+os.environ['PATH'],
                   CASE=case, CALL_LOG=str(log), DEPLOY_HOST='fixture.example',
                   SMOKE_URL='http://192.0.2.1:8080', GITHUB_SHA='a'*40)
        run = subprocess.run(['bash', str(adapter), 'app.jar', 'gates'], cwd=work,
                             env=env, capture_output=True, text=True, timeout=20)
        assert run.returncode == expected, (case, run.returncode, expected, run.stdout, run.stderr)
        calls = log.read_text()
        if case == 'upload-failed':
            assert 'remote-orchestrate.sh' not in calls, 'incomplete upload activated a release'
        if case == 'success':
            assert (work/'deploy-evidence/reports/smoke.json').is_file()
        print('PASS', case)
print(f'{len(cases)} transport cases passed; no network calls')
PY

# Exercise the actual rollback guard, with only its fixed application path
# relocated and OS/service commands replaced by harmless fixture executables.
python3 - "$SCRIPT_DIR/remote-rollback.sh" <<'PY'
import os
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]).read_text()
assert source.count('APP_DIR=/opt/dora-loop') == 1
with tempfile.TemporaryDirectory(prefix='dora-rollback-test-') as directory:
    root = Path(directory)
    app = root/'app'
    previous = app/'releases/aaaa'
    current = app/'releases/bbbb'
    previous.mkdir(parents=True)
    current.mkdir()
    (previous/'app.jar').write_bytes(b'previous')
    (current/'app.jar').write_bytes(b'current')
    (app/'current').symlink_to(current)
    (app/'last-good').symlink_to(current)
    executable = root/'rollback.sh'
    executable.write_text(source.replace('APP_DIR=/opt/dora-loop', 'APP_DIR='+str(app)))
    bins = root/'bin'
    bins.mkdir()
    (bins/'sudo').write_text('#!/bin/sh\nprintf "called\\n" >> "$SERVICE_CALLS"\n')
    (bins/'curl').write_text('#!/bin/sh\nprintf \'{"build":{"sha":"aaaa"}}\\n\'\n')
    for path in bins.iterdir(): path.chmod(0o755)
    calls = root/'service-calls'
    env = dict(os.environ, PATH=str(bins)+os.pathsep+os.environ['PATH'], SERVICE_CALLS=str(calls))
    run = subprocess.run(['bash', str(executable), str(previous), '', str(app/'releases/peer')],
                         env=env, capture_output=True, text=True, timeout=10)
    assert run.returncode == 1 and 'current moved' in run.stderr, (run.stdout, run.stderr)
    assert (app/'current').readlink() == current
    assert (app/'last-good').readlink() == current
    assert not calls.exists(), 'guard failure reached a service command'
    print('PASS actual rollback rejects changed ownership before modifying service')
    run = subprocess.run(['bash', str(executable), str(previous), '', str(current)],
                         env=env, capture_output=True, text=True, timeout=10)
    assert run.returncode == 0, (run.stdout, run.stderr)
    assert (app/'current').readlink() == previous
    assert (app/'last-good').readlink() == previous
    assert calls.is_file(), 'positive control never reached service commands'
    print('PASS actual rollback accepts owned release and restores last-good')
PY
