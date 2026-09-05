# 4. Thresholds are a per-service value, not compiled-in constants

Date: 2026-09-05
Status: Accepted

## Context

`Thresholds` held `public static final` fields that `DoraCalculator` read
directly. Its own javadoc said they were "defaults only... the fallback when no
per-service threshold is configured" — and no configuration mechanism existed,
so a per-service threshold was impossible without editing the file. The
documentation described a capability the code did not have.

The values are not incidental. They are rendered into every metric's
`definitionOfWrong`, which travels with every report a caller stores or serves.
A metric's own statement of what wrong means was therefore fixed at compile
time, for every consumer, forever.

A payment authorization service and an internal wiki do not share a restore
objective. Treating one number as universal is the same error as a single alert
threshold across a fleet.

## Decision

`Thresholds` becomes a record passed to the `DoraCalculator` constructor.
`Thresholds.defaults()` carries the previous values and names its band: the
DORA 2023 Elite boundaries, except time to restore, which sits at the High
boundary of one day because a one-hour target is not credible without a paging
rotation.

Two invariants are enforced rather than documented: no threshold may be
negative, and `changeFailureMaxPercent` may not exceed 100. A threshold that
cannot trip is worse than none, because it reads as coverage.

## Consequences

Done before the `api` module exists. Afterwards it is a wire-visible change —
`definitionOfWrong` strings derived from these values are served in reports and
would be persisted alongside them.

Callers wanting per-service thresholds now have somewhere to put them. Nothing
reads configuration yet; `api` will, and this is what makes that possible
without touching `core`.
