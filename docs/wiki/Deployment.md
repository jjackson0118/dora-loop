# Deployment

The target is a single VM on a private network. Nothing is published to the
internet, deliberately — see
[Authentication and exposure](Authentication-And-Exposure.md).

`deploy/provision-vm.sh` makes a bare Ubuntu 24.04 host capable of running the
service. It is in the repository rather than remembered, because a deploy target
configured by typed commands is one nobody can rebuild, and it is safe to re-run:
every step checks before it acts and prints which it took, so the output
describes the machine rather than the run.

## Provisioning and deploying are separate

Provisioning installs a JRE and Postgres, creates the accounts, and lays out the
directories. It does **not** install the application or start anything. Deploying
decides which build runs.

Keeping them apart is what makes rollback possible. Releases sit side by side
and a symlink chooses one:

```
/opt/dora-loop/releases/<sha>/app.jar
/opt/dora-loop/current -> /opt/dora-loop/releases/<sha>
```

A rollback re-points the symlink and restarts. It does not re-provision a host,
re-download anything, or depend on a build still being reproducible — which is
the only kind of rollback that works during an incident, because it was prepared
before the change rather than improvised after it.

## Two accounts, and what each can do

`dora` runs the service: a system account with no home and no login shell, which
owns nothing it does not need. `deploy` ships releases and is the only account
CI authenticates as, so what it may do is the blast radius of a compromised
pipeline. It gets three `systemctl` verbs against one unit — not `systemctl *`,
which would include `edit` and therefore arbitrary root execution.

The unit is hardened in the ordinary ways (`NoNewPrivileges`, `ProtectSystem=strict`,
an empty `CapabilityBoundingSet`, a syscall filter). Two choices are less
ordinary and are the interesting ones:

**`Wants=postgresql`, not `Requires=`.** The service must be able to run while
the database is down, because that is exactly the state the readiness probe
exists to describe. `Requires=` would take the service down with the database
and leave nothing to answer the probe — and an outage that reports nothing looks
identical to a host that is simply gone. Confirmed on the target: with Postgres
stopped, the unit stayed active and answered `503 DOWN`.

**A restart limit of five in five minutes.** Without it, a service that cannot
start — bad config, missing token, unreachable database — restart-loops forever
while looking supervised. That confusion is why the gate contract next door has
a separate exit code for *not applicable*: a supervisor that cannot tell "broken"
from "not currently able to run" restarts a service all night for no reason.

## Where the secrets come from

Two environment files, on purpose.

| File | Written by | Contains |
|---|---|---|
| `/etc/dora-loop/db.env` | provisioning, once | the database URL, user and password |
| `/etc/dora-loop/app.env` | operator bootstrap / token rotation | `DORA_INGEST_TOKEN` |

The database password is generated on the host and never transmitted. The
application and the database share a machine and speak over loopback, so it does
not need to exist anywhere else — and a secret that exists in one place cannot
drift between two. CI never learns it.

Splitting the files means a deploy cannot clobber the database credential, and
rotating the database credential does not require a release.

`app.env` is created empty at provision time so the unit can start before any
deploy has happened. Empty is safe: with no `DORA_INGEST_TOKEN` the service
refuses writes with `503` rather than accepting them unauthenticated.

Postgres listens on `127.0.0.1` only. The token filter cannot see the database,
so anything able to reach Postgres with those credentials could write deployment
events directly, bypassing the idempotency digest and every other control.

## The readiness probe answers in three seconds, and used to take thirty

Measured on this target with Postgres stopped, readiness returned the correct
`503 DOWN` after **30.016 seconds**. That is the connection pool's default
timeout: the health indicator asks for a connection and the pool keeps retrying
until it expires.

A correct verdict that arrives after the caller has given up is not a correct
verdict. Any probe with a shorter timeout sees a *timeout* rather than a
*refusal*, and those are different claims — the same distinction the gate
contract draws between finding a problem and being unable to look. Each hung
request also holds a worker thread, so a handful of probes exhausts the pool and
a database outage escalates into total unresponsiveness, taking the report
endpoint with it — and the report needs no database to answer that a service is
`UNOBSERVED`.

Bounding `connection-timeout` to 3s brought it to **3.031s**, tracking the
configured value closely enough to identify the cause by intervention rather
than by reading the code.

The test written for that fix could not fail: stopping a container unbinds its
port, the connection is refused instantly, and readiness answered in 19ms with
the defect restored. The test that carries the property instead points the
datasource at an unrouted address, so packets are dropped rather than refused
and the pool is forced to be the thing that gives up.

## How CI reaches the target

A GitHub-hosted runner cannot reach this VM: it sits on a private bridge behind
another host, and nothing about it is exposed to the internet. The obvious fix —
a self-hosted runner — is the wrong one here, because both repositories are
**public**, and a self-hosted runner on a public repository puts an executor for
repository-supplied code inside the network it can reach.

So the runner comes to the tailnet instead. For the duration of one workflow run
it joins as an ephemeral node tagged `tag:ci`, connects over Tailscale SSH to the
node tagged `tag:deploy-target`, ships a release, and disappears. The policy
fragment is in [`deploy/tailscale-policy.hujson`](https://github.com/jjackson0118/dora-loop/blob/main/deploy/tailscale-policy.hujson).

Two properties are worth stating because they are the reason for the choice:

**No SSH private key exists.** Tailscale SSH authorises by tailnet identity
against the policy, so there is no long-lived key in GitHub secrets to leak,
rotate, or forget to rotate. The credential CI holds is an OAuth client that
mints a short-lived, tagged, ephemeral node and nothing else.

**The grant is one tag to one tag on one port.** Not an address — an address is a
fact about today's network, and a tag is a statement about the machine's role,
which survives a rebuild. Port 8080 is deliberately not opened to CI: the smoke
test runs on the target against its consumer-facing bridge listener. This verifies
that listener and product behavior; it does not establish reachability from the
consumer's machine.

Combined with the `deploy` account's three `systemctl` verbs, that policy is the
complete blast radius of a compromised pipeline.

## Deploy, smoke, rollback

Three scripts in `deploy/`, plus the `smoke` gate from
[delivery-gates](https://github.com/jjackson0118/delivery-gates). The remote
halves are separate files rather than strings built by the caller, so they are
covered by the shellcheck gate and can be run directly against a target during
development — the logic is testable independently of the transport.

**`remote-release.sh`** activates a release, and refuses to activate one it
cannot vouch for. It verifies the jar's sha256 (a truncated transfer is a file
that exists, looks plausible, and fails at class-load time minutes later,
looking like a code problem); it verifies that `app.env` already carries an ingest token before
flipping the symlink; and once restarted it reads back both
the symlink and `/actuator/info` and fails if either disagrees with what was
deployed.

That read-back is the whole point. `ln` reports success for a link nobody
follows, and `systemctl` reports success for a unit that starts and then serves
the wrong thing — which is exactly what a rehearsal produced before these
checks existed.

**`decide.sh`** reads the smoke gate's JSON report and decides. It reads the
report rather than the CI step's status because `run-gate.sh` maps both exit 1
and exit 2 to failure for the orchestrator: an orchestrator has two states and
the contract has four, so by the time a workflow step has a status, the
distinction this decision turns on has already been thrown away.

| smoke | decision | why |
|---|---|---|
| 0 | keep, `VERIFIED` | it served, and served what we shipped |
| 1 | **roll back** | it answered, and the answer was wrong |
| 2 | keep, `UNVERIFIED`, fail the job | nobody found out |
| 3 | keep, `UNVERIFIED`, fail the job | not-applicable after a deploy means `SMOKE_URL` is unset |
| no report | keep, `UNVERIFIED`, fail the job | a gate that produced no evidence has told us nothing |

The exit-2 row is the arguable one. Rolling back on "could not measure" means a
missing `curl` or a DNS blip reverts a healthy release — an automated outage
caused by the machinery meant to prevent outages. And an unmeasured deploy is
not evidence of a defect, so recording one would inflate change failure rate
with a failure that never happened. So the release stays, the record says
`UNVERIFIED`, and the job fails loudly for a person. Something is deployed,
nobody checked it, and the record says exactly that instead of guessing in
either direction.

**`remote-rollback.sh`** takes the previous release as an argument rather than
discovering it, because it was recorded before the deploy that is now being
undone — during an incident is the worst moment to start working out where to
go back to. It re-points a symlink and restarts: no fetching, no rebuilding, so
it works when the network is down, when the registry is down, and when the
build that produced the current release is no longer reproducible. That is why
releases are kept side by side.

Build identity is stamped into the jar. Rollback preserves `app.env` and reads
identity from the restored application's `/actuator/info` endpoint.


## CI configuration

Repository Actions secrets: `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`.
Create the Tailscale OAuth client with writable `auth_keys` scope and `tag:ci`.
Repository Actions variables: `DEPLOY_HOST` (the VM's Tailscale IP or hostname)
and `SMOKE_URL` (the VM's consumer-facing bridge URL, including port 8080).
Keep deployment-specific addresses in these variables, outside this public repo. The latter is intentionally explicit rather than
silently defaulting to loopback. Apply the policy fragment and provision the
target first. Bootstrap one verified release and the ingest token on the host;
CI preserves that token and requires a populated `last-good`.

Only pushes to main deploy, after both the reusable pinned gates and the deploy
script tests pass. PR jobs never join the tailnet. GitHub concurrency serializes
`deploy-vm` with cancellation disabled. A target-side orchestration lock spans
staging, activation, smoke and recovery. Manual mechanisms still have their
existing deploy lock and compare-and-swap checks.

`ci-deploy.sh` streams a complete bundle through Tailscale SSH. Failed upload
never invokes activation. `remote-orchestrate.sh` checks the received jar,
publishes a new immutable release directory, snapshots `last-good` before
activation, and passes that snapshot to both activation and recovery. Existing
release bytes cannot be overwritten. A rerun whose SHA is already current fails
unverified for operator inspection instead of claiming a new verified deployment.

Smoke uses a new remote report directory and a timestamp taken on that same
machine. Both its process exit and report must agree. Explicit remote recovery
replaces a broad GitHub `if: failure()` rollback: activation failure after the
flip or an agreed smoke defect triggers recovery; missing/unreadable/inconclusive
evidence keeps the candidate and fails the job. Recovery checks candidate
ownership again under the rollback lock, refusing to withdraw a peer's release.
The activation script's `last-good` means activation/read-back verified; it
advances before smoke and remains advanced for KEEP_UNVERIFIED. It must not be
interpreted as smoke verification.

Cancellation or SSH loss can interrupt these mechanisms. Such a run fails and
requires inspecting `current`, `last-good` and the retained remote bundle;
there is no claim of automatic recovery across SIGKILL. Remote bundles are
retained under `/tmp/dora-ci.*` for recovery evidence (and need periodic operator
cleanup). The Actions artifact contains the prior target, start epoch, decision,
and smoke report when available. The decision file describes the smoke verdict,
not rollback completion; recovery success or failure is in the job log and exit
status. The application token is neither uploaded nor
included in that artifact.

## First successful CI deployment

On 2026-09-06, [run 34049531919, attempt 2](https://github.com/jjackson0118/dora-loop/actions/runs/34049531919/attempts/2)
completed successfully for commit `49ebcf8058d02e212befdf92cb6bd944cc7dee3e`.
The gates, artifact build, ephemeral Tailscale connection, release activation,
smoke verification, and evidence upload passed. An independent read of the VM
confirmed HTTP 200 readiness and that `/actuator/info`, `current`, and
`last-good` all identified that same commit.

Attempt 1 stopped at authentication because the repository secret values were
misconfigured. It never activated a release. Correcting the values and rerunning
the failed job produced the successful run above; the successful build alone
was not treated as evidence of deployment.

The deployment suite also passed 44 isolated checks: 19 decision cases,
14 orchestration scenarios, 9 transport scenarios, and 2 controls exercising
the actual rollback ownership guard. Those cases cover failures without
injecting defects into the live VM. This successful live run demonstrates the
happy path; it does not claim a live CI rollback rehearsal or external-user
reachability. Smoke runs on the target against its configured bridge listener.

Event reporting and its orthogonal verification field remain separate work.
The service is deployed; the pipeline does not yet post its own deployment events.
