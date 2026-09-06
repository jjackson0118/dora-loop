# Operational failure rehearsal

This procedure exercises the real target orchestrator, Java service, PostgreSQL, systemd, smoke gate, rollback, and HTTP event delivery in a disposable LXD VM. It does **not** exercise GitHub Actions or Tailscale transport. A successful GitHub deployment and these target-side failure rehearsals are separate evidence.

## Prerequisites and isolation

Use a Linux host with Python 3.12+, LXD access, and both repositories. The disposable VM must be named `dora-rehearsal`, have **no NIC and no profiles**, and contain the provisioned service, database, deploy account, and these actual release jars:

- baseline: `9a7159ec65eb2df1d2bba218d529edfb360affef`
- candidate: `259a4f200215b15c622da727fe29d2b802fa1434`

The harness deliberately fixes historical refs so its results remain reproducible. It loads committed deploy scripts from the candidate and gates from `a780e1d80ef173d22c914d54c52e0eec2431a250`. It does not build jars. An operator must supply artifacts genuinely built from those commits; never rename another artifact to claim that identity.

One preparation option is an offline-network clone of an existing provisioned VM. Create the clone stopped, without profiles, inspect its expanded devices and remove any NIC before boot. A live disk copy requires successful PostgreSQL crash recovery validation before testing. Do not stop or reconfigure the serving source VM. A clone contains private credentials and data: retain it locally, never publish its disk or configuration, and destroy it after collecting the allowlisted evidence. The harness neither creates nor deletes VMs.

An independent fresh installation with those releases is also suitable. This is an operator-level deployment rehearsal, not the basic local quickstart.

## Run

From the dora-loop repository:

```sh
python3 tools/rehearse-deployment.py \
  --target dora-rehearsal \
  --gates-repo /path/to/delivery-gates \
  --output /tmp/dora-rehearsal-evidence
```

The output directory must not exist. Both host and guest refuse a networked target. Only the exact disposable name is accepted by the host tool. Each scenario resets to the actual baseline and uses a fresh bundle and explicit local rehearsal event ID. Existing database rows are retained; assertions compare count deltas.

## Faults and expected evidence

| Scenario | Injection | Required observation |
|---|---|---|
| Smoke defect | Loopback listener forwards real readiness/build identity and returns HTTP 503 for the product endpoint | Exit 1; baseline restored; ROLLED_BACK / VERIFIED stored |
| Inconclusive smoke | An unresolvable .invalid hostname; curl exit 6 checked first | Exit 2; candidate retained; SUCCESS / UNVERIFIED stored |
| Event delivery failure | Disclosed gate wrapper runs the original smoke, then changes the clone token file while the running application retains its original token | Exit 2; candidate retained; no receipt or added database row |
| Replay | Restore original token file and submit the saved event twice | STORED then DUPLICATE; one additional row; payload bytes unchanged |

Connection refusal and timeout are smoke findings, not inconclusive measurements, so they are not substitutes for the DNS injection.

The delivery-failure wrapper is test-only. No production script is modified. Credential contents are held in guest memory and restored in a finally block; they are never printed or collected. Abruptly killing the guest driver can bypass restoration, which is another reason this procedure is restricted to a disposable isolated target.

## Review and cleanup

The output contains execution.log and evidence.json. Review exact refs, decisions, exit codes, served build identities, event outcome/verification, receipt, row-count deltas, and unchanged replay hash. The injected listener defect is not a discovered application regression. This rehearsal does not generate incident events or prove restore-time metrics.

If the harness fails, retained partial evidence is not a pass. Inspect the disposable target and correct the harness/setup, then repeat into a new output directory. The serving demo remains untouched.

After review, stop and delete only the explicitly identified disposable VM using your normal LXD procedure. Publish a sanitized verification record, not its disk, credentials, or unrestricted logs.

## Observed verification — 2026-09-06

All scenarios above passed against an isolated copy with no NIC or profiles,
PostgreSQL recovered successfully, and initial readiness returned HTTP 200.
The [machine-readable evidence](evidence/2026-09-06-operational-rehearsal.json)
records exact served identities, decisions, events, receipts and source hashes.

- Smoke defect: exit 1; baseline restored; ROLLED_BACK / VERIFIED; row count 7 → 8.
- Inconclusive smoke: exit 2; candidate retained; SUCCESS / UNVERIFIED; 8 → 9.
- Delivery failure: exit 2; candidate retained; no receipt; count stayed 9.
- Replay after restoring the credential: STORED then DUPLICATE; count 10 then 10,
  with unchanged payload bytes.

The initial seven rows came from the copied database; none were removed. The
serving demo database was not used for these rehearsals. An earlier harness
attempt stopped before activation because its parent directory used the wrong
group; the corrected run above passed. Partial output from that attempt is not
counted as evidence. An independent agent reviewed the isolation controls and
resulting evidence.

These are target-side operational tests. They do not claim failure scenarios
traversed GitHub/Tailscale, an actual application regression, or measured incident
recovery time. The live GitHub happy path is recorded separately in
[Deployment](wiki/Deployment.md).
