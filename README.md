# dora-loop

A delivery pipeline that measures itself.

`dora-loop` computes the four DORA metrics — deployment frequency, lead time for
changes, change failure rate, and time to restore — from deployment and incident
events. It is deployed by the GitHub Actions pipeline that feeds it, so the
pipeline is both the subject and the source of its own measurements.

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
outage *improve* the number. There is a test named exactly that.

Lead time is measured from **commit-authored time**, not deploy time, which
means the pipeline has to carry `git log -1 --format=%aI` through to the event.
Most implementations skip this and quietly measure something else.

## Verification

Every guard has a negative control — a test asserting it *rejects* the bad case,
not merely that the good case passes. A constraint never observed refusing
anything is not known to work.

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
api/    Spring Boot service: event ingest, metrics endpoint, actuator health.
```

## License

Apache-2.0.
