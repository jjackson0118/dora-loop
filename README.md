# dora-loop

A library that computes the four DORA metrics — deployment frequency, lead time
for changes, change failure rate, and time to restore — from deployment and
incident events.

**Status: `core` and `api` are built; nothing is deployed yet.** The domain
model, the metrics, and an HTTP service that ingests events and serves reports
all exist and are tested. There is still no deploy step — CI runs gates and
publishes reports, and nothing runs the service anywhere.
The intended end state is that the pipeline deploying this service also posts its
own deployment events back into it — the pipeline as both subject and source of
its own measurements. That loop is **not built**. See the Roadmap.

## Why it exists

Delivery dashboards rarely fail by reporting a wrong number. They fail by
reporting a **green** one that stands in for a measurement that never happened.
A metric computed from zero observations renders as zero, zero reads as health,
and nobody asks the question again.

So the contract here is enforced in the type system rather than left to the
caller's discipline. `Metric` refuses to be constructed if either rule is broken:

- **Zero observations render `UNOBSERVED`, never `OK`.** An empty window produces
  four unobserved metrics, not four green zeros.
- **Every metric must carry a `definitionOfWrong`.** A signal that cannot say
  what wrong looks like cannot alert, and a signal that cannot alert is
  decoration.

Both failures are silent in the usual implementation, and both read as health.

### What that buys you

Open incidents are excluded from time-to-restore rather than counted as zero.
Counting an unresolved incident as a zero-duration restore would let an ongoing
outage *improve* the number. Two tests cover it: "an open incident does not count
as a zero-duration restore" and "only open incidents means UNOBSERVED, never a
healthy zero".

Lead time is measured per **change**, from each commit's author date. A
deployment carries every commit in the range since the last one, so a two-week
branch is not collapsed into the minutes since its final "fix typo" commit.

This is why the obvious integration is wrong: `git log -1 --format=%aI` returns
the merge commit on a squash or merge, which reports a lead time near zero. The
pipeline has to track the previously deployed SHA and pass the whole range. See
[ADR 0002](docs/adr/0002-lead-time-is-per-change.md).

## Verification

Every guard has a negative control — a test asserting it *rejects* the bad case,
not merely that the good case passes. A constraint never observed refusing
anything is not known to work. That covers all four `Metric` invariants,
including the negative observation count, which was the one without a control
until a mutation pass found it; plus non-positive windows and `Metric.observed`
with no observations.

Two exceptions, named rather than quietly included. **Incident ordering is not a
rejection at all** — per [ADR 0003](docs/adr/0003-suspect-input-is-a-signal.md)
an implausible incident is accepted and marked, so its test asserts acceptance;
listing it here as a guard with a negative control was wrong. And **the median
of an empty list has no control**, because every caller checks `isEmpty()` first
and renders `UNOBSERVED`, so the throw is unreachable. It stays for a future
caller that forgets, and the source says so — but an unreachable guard is not a
tested one, and claiming otherwise is the failure this file is about.

The guards are additionally verified by perturbation: remove the
zero-observations check and `MetricTest` goes red; count open incidents as
zero-duration restores and two `DoraCalculatorTest` cases go red. Revert, green.

`api` is tested against a real Postgres through a real socket, because the parts
of it that can be wrong are the parts an `ObjectMapper` never touches: the
migration, the SQL, the transaction boundary and the status codes. The
migration's own version row is asserted rather than the existence of the tables,
since asserting the tables would also pass under `ddl-auto` — which would mean
the checked-in SQL was decorative.

Those tests are perturbed too. The mutation that matters most is emptying
`insertDeployment`: it left the pre-integration-test suite entirely green, and
now fails 24 tests.

That number has been wrong twice. It was "seven" from the 14-test era, carried
through three commits that grew the suite; it was then corrected to 19, which
was `ApiIntegrationTest` alone — the module I happened to run — while
`IngestGapTest` contributed five more. The sentence claiming it had been
re-measured was itself written from a partial measurement. `gates/jvm-test.sh`
runs `./gradlew test --continue` precisely so one module's failure cannot
truncate the count, and it would have reported 24 the first time. A write boundary is verified by injecting a Postgres trigger that
raises mid-write and asserting nothing survives, rather than by reading the
annotation — a review that read the annotation source in isolation concluded the
boundary was inert, and measurement showed it was not.

## Build

Requires JDK 21. Gradle comes from the wrapper — nothing to install.

```bash
./gradlew build      # compile + test
./gradlew :core:test # core only
```

## Layout

```
core/   Domain model and the four metrics. No Spring, no I/O — pure and directly testable.
api/    Spring Boot service: event ingest, report endpoint, actuator health. Postgres behind Flyway.
```

`api` never lets a framework reach `core`. Requests and responses are its own
DTOs, hand-mapped: serializing the core records directly would make their
component names a wire contract that any refactor silently breaks, and it is
the first step toward Jackson and validation annotations on domain types.

Two serialization rules exist because getting them wrong reintroduces this
project's central failure through a default. An unobserved metric serializes
`"value": null`, **explicitly present** — an omitted key deserializes to `0` in
most typed clients. And there is no boolean health field, because a boolean
cannot carry three states and whichever value it took for `UNOBSERVED` would
conflate it with either healthy or degraded. Both are asserted by tests.

Ingest is strict on unknown fields. A producer still sending the pre-ADR-0002
`commitAuthoredAt` would otherwise be accepted with an empty `changes` list — a
deployment contributing no lead-time observations, which renders as *fewer
observations* rather than as an error.

### What is protected, and what is deliberately not

Writes require a shared secret in `X-Dora-Ingest-Token`, set as
`DORA_INGEST_TOKEN`. There is no default value, and no token configured means
writes are **refused** — `503`, "ingest is not configured" — rather than
accepted unauthenticated. A token whose default is "off" is a control that
reads as coverage without being one, which is the failure this project is
about; and the default is what runs when someone deploys in a hurry.

The filter keys on the HTTP method and ignores the path: `GET`, `HEAD` and
`OPTIONS` pass, everything else must present the token. An earlier version
scoped itself to `POST` under `/api/v1/`, which meant safety depended on this
filter's idea of a path and Spring's agreeing — a raw-socket probe found four
spellings where they don't, refused only because the dispatcher happened to
answer `404`. Adding one endpoint to `management.endpoints.web.exposure.include`
would have turned that coincidence into an unauthenticated write endpoint.

A wrong token is `403`, not `401`: RFC 9110 wants a `401` to carry a
`WWW-Authenticate` challenge naming a scheme, and a shared-secret header is not
a scheme. The comparison is constant-time, because response latency otherwise
leaks the secret one byte at a time.

Three things are open on purpose, and should be read as decisions rather than
oversights:

| Open | Why |
|---|---|
| `GET /api/v1/services/{service}/report` | Publishing the report is the reason to deploy this at all. |
| `GET /actuator/health`, `/health/readiness`, `/health/liveness` | A probe that needs a credential is a probe that fails during the incident it exists for. `show-details: never`. |
| `GET /actuator/info` | Build identity only. |

What this is **not**: it is one shared secret, with no rotation, no expiry, no
per-producer identity and no rate limiting, so anyone holding it can write
anything and nothing distinguishes one holder from another. That is a
deliberate stopping point for a service whose write surface is a CI job, not a
claim that it is sufficient for more.

### Replays, retries and corrections

Three different things arrive under an id that already exists, and collapsing
them loses information in the direction that flatters the metrics:

| | Response | Meaning |
|---|---|---|
| New event | `201` `STORED` | Written. |
| Identical payload | `200` `DUPLICATE` | A retry. Nothing written. |
| Legal correction | `200` `UPDATED` | `SUCCESS` → `ROLLED_BACK`, or an open incident being resolved. |
| Anything else | `409` | Two different claims about one event. |

`201` is reserved for a write that happened, because a client counting `201`s to
know what it recorded would otherwise be counting its own retries.

The two corrections exist because both arrive *after* the event they describe.
A rollback is reported later than the deployment it undoes, and `ROLLED_BACK` is
one of only two numerator terms in change failure rate — refusing the correction
made the service look better the more often it had to be rolled back. An
incident's `resolvedAt` arrives later than the incident, so resolution is a
re-POST rather than a `PATCH` endpoint that sets one field.

Corrections are narrow on purpose. `FAILED_ROLLOUT` means the change never
reached production, so it cannot follow a success; `ROLLED_BACK` is terminal;
and a resolution time that has been published cannot be moved or cleared. The
`UPDATE` carries `AND resolved_at IS NULL` so that last one is enforced by the
database rather than only by the check above it.

The idempotency digest is over a length-prefixed encoding of the payload, not
over `toString()`. A record renders as `Name[a=1, b=2]`, so `", environment="`
inside a field value forges a field boundary: two genuinely different
deployments produced the same digest, and the second was answered `201` and
discarded.

`corePurityCheck` in the root build asserts that, by failing if `:core` ever
acquires a runtime dependency. Until it existed the claim was prose and nothing
checked it — and it is load-bearing rather than tidy: the
dependency-vulnerability gate's denominator is a component count, so "core has
none" is precisely why the `api` module has to supply a real graph instead of
the scanner quietly scanning nothing.

A module is added to `settings.gradle.kts` only once it contains sources. An
included module with no source set reports `NO-SOURCE` and still rolls up into
`BUILD SUCCESSFUL` — the same defect class this project is about — so
`emptyModuleCheck` in the root build fails if that rule is broken.

## Decisions

Non-obvious choices are recorded in [`docs/adr/`](docs/adr/):

1. [Change failure rate joins incidents, it does not count failed rollouts](docs/adr/0001-change-failure-rate-joins-incidents.md)
2. [Lead time is measured per change, not per deployment](docs/adr/0002-lead-time-is-per-change.md)
3. [Implausible input is quarantined and surfaced, not rejected](docs/adr/0003-suspect-input-is-a-signal.md)
4. [Thresholds are a per-service value, not compiled-in constants](docs/adr/0004-thresholds-are-configurable.md)

## Roadmap

- A deploy step in the pipeline that posts its own `DeploymentEvent` back here,
  which is what closes the loop the name refers to

## License

Apache-2.0.
