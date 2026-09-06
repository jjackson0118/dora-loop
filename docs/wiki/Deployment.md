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
| `/etc/dora-loop/app.env` | every deploy | `DORA_INGEST_TOKEN`, `DORA_BUILD_SHA` |

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
test runs on the target over loopback, because a smoke test that exercises a port
no user goes through proves the wrong thing.

Combined with the `deploy` account's three `systemctl` verbs, that policy is the
complete blast radius of a compromised pipeline.
