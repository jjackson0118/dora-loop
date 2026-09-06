# ADR 0005: Verification is independent deployment evidence

Status: accepted.

## Context

An inconclusive smoke check does not prove a rollout failed and must not
invent a rollback. The deployment outcome and the evidence supporting it are
separate facts. Existing producers and stored rows contain no verification
claim; a migration cannot retrospectively claim their checks passed.

## Decision

Keep `Outcome` three-valued: `SUCCESS`, `FAILED_ROLLOUT`, `ROLLED_BACK`. Add
optional deployment ingest field `verification`: `VERIFIED` or `UNVERIFIED`.
Omitted or JSON null means `UNVERIFIED`; blank, lowercase and unknown values
are rejected. `VERIFIED` means the producer has conclusive evidence for the
reported outcome, including a failed rollout or rollback. It does not mean
success. The API trusts the authenticated producer's claim; it does not run a
smoke check itself.

The new `data_quality.unverified_deployments` signal counts UNVERIFIED
production deployments for the requested service in the report's half-open
window, regardless of outcome. `observedN` counts all deployments inspected.
No deployments means UNOBSERVED with null value; all verified means OK with
zero; any unverified means DEGRADED. It is included in report summaries but
is separate from the four DORA metrics. Existing DORA formulas are unchanged:
those metrics reflect reported outcomes, and their evidence limitation is
visible in the separate quality signal rather than silently changing their
denominators.

A full-payload correction may advance UNVERIFIED to VERIFIED, independently
of or together with SUCCESS to ROLLED_BACK. Immutable fields cannot change.
Neither outcome nor verification may regress. A stale producer's omitted
verification after verification has advanced is a 409, including when that
producer reports a rollback; it must preserve the latest verification claim
when retrying. Row locks serialize comparison and update so racing corrections
cannot erase stronger evidence.

## Compatibility and migration

Flyway V2 adds a non-null, constrained verification column defaulting to
UNVERIFIED. V1 is unchanged; existing payload hashes and rows are retained.
Legacy and explicit UNVERIFIED payloads retain the exact deployment/v1
canonical encoding. VERIFIED uses deployment/v2 with a verification field.
Existing clients can continue inserting without the field and retrying legacy
events. API tests migrate a real V1 database, replay a literal old hash, and
verify both correction and old-client inserts after migration.

The migration is additive, so an older application can still read and insert.
However, an old binary neither understands nor enforces verification correction
rules and must not process corrections to verified events during rollback.
After returning to this version, duplicate recognition checks the stored event
as well as the digest, preventing an old binary's v1 hash from hiding newer
verification evidence. Schema rollback or claiming that older code preserves
new semantics is not supported.
