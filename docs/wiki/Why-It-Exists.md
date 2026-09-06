# Why it exists

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
[ADR 0002](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0002-lead-time-is-per-change.md).

### Deployment evidence

A reported outcome does not guarantee its verification completed.
`data_quality.unverified_deployments` counts production events without
conclusive evidence in the same service and time window. No events is
UNOBSERVED, all verified is an observed zero, and any unverified event is
DEGRADED. This includes failed rollouts. The four DORA formulas retain their
reported-outcome semantics; the quality signal makes the evidence gap visible
alongside them and in the summary.
