# Replays, retries and corrections

Three different things arrive under an id that already exists, and collapsing
them loses information in the direction that flatters the metrics:

| | Response | Meaning |
|---|---|---|
| New event | `201` `STORED` | Written. |
| Identical payload | `200` `DUPLICATE` | A retry. Nothing written. |
| Legal correction | `200` `UPDATED` | `SUCCESS` → `ROLLED_BACK`, or an open incident being resolved. |
| Anything else | `409` | Two different claims about one event. |

`201` is reserved for a write that happened, because a client counting `201`s to
know what it recorded would otherwise be counting its own retries.

The two corrections exist because both arrive *after* the event they describe.
A rollback is reported later than the deployment it undoes, and `ROLLED_BACK` is
one of only two numerator terms in change failure rate — refusing the correction
made the service look better the more often it had to be rolled back. An
incident's `resolvedAt` arrives later than the incident, so resolution is a
re-POST rather than a `PATCH` endpoint that sets one field.

Corrections are narrow on purpose. `FAILED_ROLLOUT` means the change never
reached production, so it cannot follow a success; `ROLLED_BACK` is terminal;
and a resolution time that has been published cannot be moved or cleared. The
`UPDATE` carries `AND resolved_at IS NULL` so that last one is enforced by the
database rather than only by the check above it.

The idempotency digest is over a length-prefixed encoding of the payload, not
over `toString()`. A record renders as `Name[a=1, b=2]`, so `", environment="`
inside a field value forges a field boundary: two genuinely different
deployments produced the same digest, and the second was answered `201` and
discarded.
