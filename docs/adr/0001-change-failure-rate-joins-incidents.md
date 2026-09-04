# 1. Change failure rate joins incidents, it does not count failed rollouts

Date: 2026-09-04
Status: Accepted

## Context

The first implementation computed change failure rate as the share of
production deployments whose `Outcome` was `FAILURE`. That is deploy-job
failure rate, and it is a different measurement wearing the right name.

DORA defines change failure rate as the percentage of changes to production
that result in degraded service requiring remediation. The two measurements
move in opposite directions:

- A rollout the pipeline catches and fails is the pipeline **working**. Under
  the old implementation it counted against the team.
- A rollout that succeeds cleanly and pages someone two hours later is the
  failure this metric exists to count. Under the old implementation it was
  invisible.

`IncidentEvent.causedByCommitSha` -- the join key the correct definition needs
-- existed in the model and was read nowhere. The data model was right and the
calculator took the easy path.

## Decision

- `Outcome` becomes three-way: `SUCCESS`, `FAILED_ROLLOUT`, `ROLLED_BACK`.
- Denominator: deployments that **reached production** (`SUCCESS` +
  `ROLLED_BACK`). A failed rollout leaves both halves, because a change that
  never reached users can neither succeed nor fail in production.
- Numerator: deployments that were rolled back, plus deployments carrying a
  commit that an incident blames.
- Incidents are **not** window-filtered when joined. An incident caused by a
  deploy inside the window is frequently detected after it; excluding those
  would make the metric improve because an outage was recent.

## Consequences

The metric now requires incident data to be meaningful. With no incidents
recorded it reports a genuine zero over an observed denominator, which is
correct but easy to misread as "no failures" when it may mean "no incident
reporting." That gap belongs to a future signal on incident-feed liveness.

Right-censoring remains: an incident caused by a deploy in the window and not
yet detected cannot be counted. The metric is a lower bound. `observedN`
carries the denominator so the reader can see what it is a bound over.
