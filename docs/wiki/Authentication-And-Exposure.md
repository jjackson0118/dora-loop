# Authentication and exposure

Writes require a shared secret in `X-Dora-Ingest-Token`, set as
`DORA_INGEST_TOKEN`. There is no default value, and no token configured means
writes are **refused** — `503`, "ingest is not configured" — rather than
accepted unauthenticated. A token whose default is "off" is a control that
reads as coverage without being one, which is the failure this project is
about; and the default is what runs when someone deploys in a hurry.

The filter keys on the HTTP method and ignores the path: `GET`, `HEAD` and
`OPTIONS` pass, everything else must present the token. An earlier version
scoped itself to `POST` under `/api/v1/`, which meant safety depended on this
filter's idea of a path and Spring's agreeing — a raw-socket probe found four
spellings where they don't, refused only because the dispatcher happened to
answer `404`. Adding one endpoint to `management.endpoints.web.exposure.include`
would have turned that coincidence into an unauthenticated write endpoint.

A wrong token is `403`, not `401`: RFC 9110 wants a `401` to carry a
`WWW-Authenticate` challenge naming a scheme, and a shared-secret header is not
a scheme. The comparison is constant-time, because response latency otherwise
leaks the secret one byte at a time.

**Every** `GET`, `HEAD` and `OPTIONS` is open — at every path, present and
future. That is the direct consequence of keying on the method, and it is worth
stating plainly rather than listing three endpoints and letting a reader infer
the rest. What exists to be read is therefore the real control on the read side,
and it is a separate mechanism:

| Open | Why |
|---|---|
| `GET /api/v1/services/{service}/report` | Publishing the report is the reason to deploy this at all. |
| `GET /actuator/health`, `/health/readiness`, `/health/liveness` | A probe that needs a credential is a probe that fails during the incident it exists for. `show-details: never`. |
| `GET /actuator/info` | Build identity only. |

Nothing else on the actuator exists. `management.endpoints.enabled-by-default`
is `false` and `health` and `info` are enabled by name, so widening
`exposure.include` — even to `*` — publishes nothing new. The exposure list
alone was not enough: `GET /actuator/heapdump` is unsanitizable and returns the
ingest token in cleartext (measured: seventeen occurrences in 61 MB, no
credential of any kind), and no filter keyed on the HTTP method can ever help,
because a heap dump is a read. Closing the write half of that hazard and
calling it done would have been this project's own failure — a control that
reads as coverage without being one. `ActuatorExposureTest` sets
`exposure.include=*` and asserts the endpoints stay gone.

The deployment pipeline is also trusted to supply application code. The
`deploy` account can replace the jar and restart it as `dora`; the resulting
process receives the ingest token and database credentials and can access the
database directly, bypassing HTTP authentication and ingestion validation.
Restricted sudo commands, file permissions, and CI network access reduce direct
access but do not remove that application-level trust. See
[Deployment](Deployment.md) for the account and network boundaries.

What this is **not**: it is one shared secret, with no rotation, no expiry, no
per-producer identity and no rate limiting, so anyone holding it can write
anything and nothing distinguishes one holder from another. That is a
deliberate stopping point for a service whose write surface is a CI job, not a
claim that it is sufficient for more.
