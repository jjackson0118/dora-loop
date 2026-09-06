# Verification

Every guard has a negative control — a test asserting it *rejects* the bad case,
not merely that the good case passes. A constraint never observed refusing
anything is not known to work. That covers all four `Metric` invariants,
including the negative observation count, which was the one without a control
until a mutation pass found it; plus non-positive windows and `Metric.observed`
with no observations.

Two exceptions, named rather than quietly included. **Incident ordering is not a
rejection at all** — per [ADR 0003](https://github.com/jjackson0118/dora-loop/blob/main/docs/adr/0003-suspect-input-is-a-signal.md)
an implausible incident is accepted and marked, so its test asserts acceptance;
listing it here as a guard with a negative control was wrong. And **the median
of an empty list has no control**, because every caller checks `isEmpty()` first
and renders `UNOBSERVED`, so the throw is unreachable. It stays for a future
caller that forgets, and the source says so — but an unreachable guard is not a
tested one, and claiming otherwise is the failure this file is about.

The guards are additionally verified by perturbation: remove the
zero-observations check and `MetricTest` goes red; count open incidents as
zero-duration restores and two `DoraCalculatorTest` cases go red. Revert, green.

`api` is tested against a real Postgres through a real socket, because the parts
of it that can be wrong are the parts an `ObjectMapper` never touches: the
migration, the SQL, the transaction boundary and the status codes. The
migration's own version row is asserted rather than the existence of the tables,
since asserting the tables would also pass under `ddl-auto` — which would mean
the checked-in SQL was decorative.

Those tests are perturbed too. The mutation that matters most is emptying
`insertDeployment`: it left the pre-integration-test suite entirely green, and
now fails 24 tests.

That number has been wrong twice. It was "seven" from the 14-test era, carried
through three commits that grew the suite; it was then corrected to 19, which
was `ApiIntegrationTest` alone — the module I happened to run — while
`IngestGapTest` contributed five more. The sentence claiming it had been
re-measured was itself written from a partial measurement. `gates/jvm-test.sh`
runs `./gradlew test --continue` precisely so one module's failure cannot
truncate the count, and it would have reported 24 the first time. A write boundary is verified by injecting a Postgres trigger that
raises mid-write and asserting nothing survives, rather than by reading the
annotation — a review that read the annotation source in isolation concluded the
boundary was inert, and measurement showed it was not.

## Deployment and event reporting

[Deployment](Deployment.md) records authenticated deployment evidence and the
limits of live coverage. [Local quickstart](Local-Quickstart.md) lists the Java,
shell and Python test commands and provides a disposable API reproduction.
The deployment-helper tests exercise smoke decisions, guarded recovery,
transport, full-history payload generation and acknowledged event delivery.
Rollback publication tests inject staging and rename failures into the actual
script and check that neither changes the selected release nor restarts the
service; a successful case checks publication and restart. These are isolated
fixture proofs, distinct from a live deployment rehearsal.

## Operational failure evidence

The [isolated real-service rehearsal](https://github.com/jjackson0118/dora-loop/blob/main/docs/operational-rehearsal.md) records actual rollback, inconclusive retention, failed event delivery and stable replay. It exercises the target orchestrator; GitHub/Tailscale failure transport and incident recovery metrics remain outside that proof.
