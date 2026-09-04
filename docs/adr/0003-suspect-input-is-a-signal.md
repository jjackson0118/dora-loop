# 3. Implausible input is quarantined and surfaced, not rejected

Date: 2026-09-04
Status: Accepted

## Context

`DeploymentEvent` previously threw when `deployedAt` preceded
`commitAuthoredAt`, on the grounds that negative lead time is meaningless.

Git author dates are client-supplied. A laptop with a skewed clock, a CI runner
in the wrong timezone, or an explicit `GIT_AUTHOR_DATE` all produce one. Once
ingest is an HTTP endpoint, that guard returns 4xx and the deployment record is
**lost** -- so deployment frequency silently falls. The validation written to
protect a metric was the thing most likely to corrupt it.

## Decision

The domain no longer rejects the event. During calculation, a change whose
author date falls after its deployment is excluded from the lead-time median
and counted by a new signal, `data_quality.suspect_changes`, whose definition
of wrong is `> 0`.

## Consequences

Bad input degrades one metric visibly instead of removing a deployment
invisibly. The exclusion is itself observable, which is the property the rest
of this codebase is built around: a silent exclusion and a measurement that
never happened are the same failure.

`DoraReport` now separates `metrics()` (the four DORA metrics) from
`allSignals()` (those plus data quality), so a DORA report is not polluted by
an operational signal while the operational signal still participates in
alerting.
