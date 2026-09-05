#!/usr/bin/env bash
# Prove the new integration tests can fail. Each perturbation is applied to a
# pristine tree, the suite is run, and the tree is restored -- with a hard check
# that the edit actually changed a file, because an edit that matched nothing
# and ran green is the exact way a perturbation proof lies about itself.
set -euo pipefail
cd /mnt/raid1/dora-loop

restore() { git checkout -- api/src/main api/src/test 2>/dev/null || true; }
trap restore EXIT

run_case() {
  local name="$1" expect_test="$2"
  if ! git diff --quiet -- api/src; then :; else
    echo "RESULT $name: MUTATION-DID-NOT-APPLY (no diff) -- proof is void"
    return
  fi
  set +e
  ./gradlew :api:test --rerun-tasks > "/tmp/perturb-$name.log" 2>&1
  local rc=$?
  set -e
  # Read the JUnit XML, not the console. The console format depends on logging
  # level -- an earlier version of this script parsed it under -q, matched
  # nothing, and died on the empty pipeline instead of reporting a result.
  local failed
  failed=$(python3 - "$name" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
names = []
for f in glob.glob('api/build/test-results/test/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            names.append(tc.get('name'))
print(' | '.join(sorted(names)))
PY
)
  if [ $rc -eq 0 ]; then
    echo "RESULT $name: SURVIVED -- the suite passed with the defect present"
  elif [ -z "$failed" ]; then
    echo "RESULT $name: BUILD FAILED BUT NO TEST FAILED -- compile error, not a proof"
    tail -3 "/tmp/perturb-$name.log"
  else
    echo "RESULT $name: CAUGHT"
    echo "$failed" | tr '|' '\n' | sed 's/^ */         - /'
    case "$failed" in
      *"$expect_test"*) : ;;
      *) echo "         WARNING: expected a test mentioning '$expect_test'" ;;
    esac
  fi
  restore
}

# 1. The mutation that left the old suite green: persist nothing.
python3 - <<'EOF'
import pathlib
p = pathlib.Path("api/src/main/java/io/github/jjackson0118/doraloop/api/EventRepository.java")
s = p.read_text()
old = "    void insertDeployment(DeploymentEvent e, String payloadHash) {"
new = "    void insertDeployment(DeploymentEvent e, String payloadHash) {\n        if (true) return;"
assert old in s, "NOT FOUND: insertDeployment signature"
p.write_text(s.replace(old, new, 1))
EOF
run_case insert-is-a-noop "deployment"

# 2. Unobserved metrics omitted instead of explicit null.
python3 - <<'EOF'
import pathlib
p = pathlib.Path("api/src/main/resources/application.yml")
s = p.read_text()
old = "    default-property-inclusion: always"
new = "    default-property-inclusion: non_null"
assert old in s, "NOT FOUND: default-property-inclusion"
p.write_text(s.replace(old, new, 1))
EOF
run_case unobserved-key-omitted "unobserved"

# 3. Strictness lost to a config change.
python3 - <<'EOF'
import pathlib
p = pathlib.Path("api/src/main/resources/application.yml")
s = p.read_text()
old = "      fail-on-unknown-properties: true"
new = "      fail-on-unknown-properties: false"
assert old in s, "NOT FOUND: fail-on-unknown-properties"
p.write_text(s.replace(old, new, 1))
EOF
run_case unknown-field-accepted "ADR"

# 4. Lead time collapses to one observation per deployment (pre-ADR-0002).
python3 - <<'EOF'
import pathlib
p = pathlib.Path("api/src/main/java/io/github/jjackson0118/doraloop/api/IngestService.java")
s = p.read_text()
old = "        List<Change> changes = dto.changes().stream()\n                .map(c -> new Change(c.commitSha(), c.authoredAt())).toList();"
new = "        List<Change> changes = dto.changes().stream()\n                .map(c -> new Change(c.commitSha(), c.authoredAt())).limit(1).toList();"
assert old in s, "NOT FOUND: changes mapping"
p.write_text(s.replace(old, new, 1))
EOF
run_case only-head-commit-stored "deployment"

# 5. Implausible input rejected instead of quarantined (ADR 0003 inverted).
python3 - <<'EOF'
import pathlib
p = pathlib.Path("api/src/main/java/io/github/jjackson0118/doraloop/api/IngestDtos.java")
s = p.read_text()
old = "            @NotNull @Valid List<ChangeDto> changes"
new = "            @jakarta.validation.constraints.NotEmpty @NotNull @Valid List<ChangeDto> changes"
assert old in s, "NOT FOUND: changes field"
p.write_text(s.replace(old, new, 1))
EOF
run_case empty-changes-rejected "redeploy"

echo "DONE"
