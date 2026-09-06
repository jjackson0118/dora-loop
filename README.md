# dora-loop

A library that computes the four DORA metrics — deployment frequency, lead time
for changes, change failure rate, and time to restore — from deployment and
incident events.

**Status: CI builds, gates, deploys, and smoke-verifies the service on a private VM.**
The [first successful authenticated deployment](https://github.com/jjackson0118/dora-loop/actions/runs/34049531919/attempts/2)
completed on 2026-09-06. The served build identity matched the merged commit.
See [deployment evidence and setup](docs/wiki/Deployment.md).
The intended end state is that the pipeline deploying this service also posts its
own deployment events back into it — the pipeline as both subject and source of
its own measurements. That loop is **not built**. See the Roadmap.

## The rule everything else follows from

Zero observations produce `UNOBSERVED` — never `OK`, and never `0`. A change
failure rate of `0%` on a service with no recorded incidents reads as *perfect*
and means *nothing was measured*; those are opposite states, and a dashboard
that renders them identically hides the one you needed.

An unobserved metric serialises as `"value": null`, explicitly present, because
an omitted key deserialises to `0` in most typed clients. There is no boolean
health field, because a boolean cannot carry three states. Every metric carries
a `definitionOfWrong` describing how that particular number could mislead you.
All of it is asserted by tests, because all of it is one refactor from
silently reversing.

## Build

Requires JDK 21. Gradle comes from the wrapper — nothing to install.

```bash
./gradlew build      # compile + test
./gradlew :core:test # core only
```

## Documentation

The [wiki](https://github.com/jjackson0118/dora-loop/wiki) is the long form. Its
pages are authored in [`docs/wiki/`](docs/wiki/) in this repository and published
by CI, so they are reviewed, versioned with the code, and checked by the `docs`
gate rather than living where no gate can reach them.

| | |
|---|---|
| [Why it exists](https://github.com/jjackson0118/dora-loop/wiki/Why-It-Exists) | The signal contract, and what it buys you. |
| [Verification](https://github.com/jjackson0118/dora-loop/wiki/Verification) | What is proven, how — and what is not. |
| [Authentication and exposure](https://github.com/jjackson0118/dora-loop/wiki/Authentication-And-Exposure) | What needs a token, what is open on purpose, and the limits. |
| [Replays and corrections](https://github.com/jjackson0118/dora-loop/wiki/Replays-And-Corrections) | Retries, rollbacks and resolutions arriving under an existing id. |
| [Layout and design](https://github.com/jjackson0118/dora-loop/wiki/Layout-And-Design) | Module boundaries and the build invariants that hold them. |

## Decisions

Non-obvious choices are recorded in [`docs/adr/`](docs/adr/):

1. [Change failure rate joins incidents, it does not count failed rollouts](docs/adr/0001-change-failure-rate-joins-incidents.md)
2. [Lead time is measured per change, not per deployment](docs/adr/0002-lead-time-is-per-change.md)
3. [Implausible input is quarantined and surfaced, not rejected](docs/adr/0003-suspect-input-is-a-signal.md)
4. [Thresholds are a per-service value, not compiled-in constants](docs/adr/0004-thresholds-are-configurable.md)

## Roadmap

- Have the working deploy pipeline post its own `DeploymentEvent` back here.
  **Event reporting is not built yet**, so the measurement loop remains open.
  Add the orthogonal verification field so inconclusive checks remain explicit.
- Bounding the report query, which currently loads a service's whole history
  and windows it in memory.

Nothing here is published to the internet, deliberately. The gates that judge
this repository live in
[`delivery-gates`](https://github.com/jjackson0118/delivery-gates), which also
uses it as a fixture.

## ## License

Apache-2.0.
