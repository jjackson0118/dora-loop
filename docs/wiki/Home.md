# dora-loop

A service that computes the four DORA metrics and refuses to report a number it
did not observe.

Most delivery dashboards answer every question with a number. Ask for change
failure rate on a service that has recorded no incidents and you get `0%` —
which reads as *perfect* and means *nothing was measured*. Those two are
opposite states rendered identically, and the second one is the one you needed
to know about.

So the central rule here is that zero observations produce `UNOBSERVED`, never
`OK` and never `0`. An unobserved metric serialises as `"value": null`,
explicitly present, because an omitted key deserialises to `0` in most typed
clients and reintroduces the whole problem through a default. There is no
boolean health field either: a boolean cannot carry three states, and whichever
value it took for `UNOBSERVED` would conflate it with either healthy or
degraded. Both rules are asserted by tests, because both are one careless
refactor from silently reversing.

Every metric also carries a `definitionOfWrong` — the specific way that
particular number could be misleading — so a consumer is told how to distrust it
rather than left to assume.

This is also the repository that
[delivery-gates](https://github.com/jjackson0118/delivery-gates) runs against as
a fixture, so the two are worth reading together: one argues that a check nobody
has seen fail proves nothing, the other that a metric nobody has seen abstain
proves nothing.

## Where to start

- **[Why it exists](Why-It-Exists.md)** — the signal contract, and what it buys
  you.
- **[Verification](Verification.md)** — what is actually proven, how, and by
  what. Including the parts that are not.
- **[Authentication and exposure](Authentication-And-Exposure.md)** — what
  requires a token, what is deliberately open, and the honest limits.
- **[Replays, retries and corrections](Replays-And-Corrections.md)** — three
  different things arrive under an id that already exists, and collapsing them
  loses information in the direction that flatters the metrics.
- **[Layout and design](Layout-And-Design.md)** — module boundaries, and the
  build invariants that keep them from eroding.

Non-obvious decisions are recorded as ADRs in the repository:
[0001 change failure rate](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0001-change-failure-rate-joins-incidents.md),
[0002 lead time per change](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0002-lead-time-is-per-change.md),
[0003 suspect input is a signal](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0003-suspect-input-is-a-signal.md),
[0004 thresholds are configurable](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0004-thresholds-are-configurable.md).

## Honest limits

The loop the name refers to does not close yet. Nothing is deployed, so the
pipeline does not currently post its own `DeploymentEvent` back here — that is
the next piece of work, not a description of what runs today.

Nothing is published to the internet, deliberately. The report is readable
without a token by design, and the service is reachable only on a private
network.
