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
anything is not known to work. That covers all four `Metric` invariants and the
four remaining domain guards: incident ordering, non-positive windows,
`Metric.observed` with no observations, and the median of an empty list.

The guards are additionally verified by perturbation: remove the
zero-observations check and `MetricTest` goes red; count open incidents as
zero-duration restores and two `DoraCalculatorTest` cases go red. Revert, green.

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
