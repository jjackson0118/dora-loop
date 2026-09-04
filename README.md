# dora-loop

A library that computes the four DORA metrics — deployment frequency, lead time
for changes, change failure rate, and time to restore — from deployment and
incident events.

**Status: `core` only.** The domain model and the metrics are built and tested.
There is no service, no ingest, no persistence, and no deploy step: CI runs gates
and publishes reports, and `DoraCalculator` has no caller outside the test suite.
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
```

A module is added to `settings.gradle.kts` only once it contains sources. An
included module with no source set reports `NO-SOURCE` and still rolls up into
`BUILD SUCCESSFUL` — the same defect class this project is about — so
`emptyModuleCheck` in the root build fails if that rule is broken.

## Roadmap

- `api` — Spring Boot service: event ingest, metrics endpoint, actuator health
- A deploy step in the pipeline that posts its own `DeploymentEvent` back here,
  which is what closes the loop the name refers to

## License

Apache-2.0.
