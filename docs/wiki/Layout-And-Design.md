# Layout and design

```
core/   Domain model and the four metrics. No Spring, no I/O — pure and directly testable.
api/    Spring Boot service: event ingest, report endpoint, actuator health. Postgres behind Flyway.
```

`api` never lets a framework reach `core`. Requests and responses are its own
DTOs, hand-mapped: serializing the core records directly would make their
component names a wire contract that any refactor silently breaks, and it is
the first step toward Jackson and validation annotations on domain types.

Two serialization rules exist because getting them wrong reintroduces this
project's central failure through a default. An unobserved metric serializes
`"value": null`, **explicitly present** — an omitted key deserializes to `0` in
most typed clients. And there is no boolean health field, because a boolean
cannot carry three states and whichever value it took for `UNOBSERVED` would
conflate it with either healthy or degraded. Both are asserted by tests.

Ingest is strict on unknown fields. A producer still sending the pre-ADR-0002
`commitAuthoredAt` would otherwise be accepted with an empty `changes` list — a
deployment contributing no lead-time observations, which renders as *fewer
observations* rather than as an error.

`corePurityCheck` in the root build asserts that, by failing if `:core` ever
acquires a runtime dependency. Until it existed the claim was prose and nothing
checked it — and it is load-bearing rather than tidy: the
dependency-vulnerability gate's denominator is a component count, so "core has
none" is precisely why the `api` module has to supply a real graph instead of
the scanner quietly scanning nothing.

A module is added to `settings.gradle.kts` only once it contains sources. An
included module with no source set reports `NO-SOURCE` and still rolls up into
`BUILD SUCCESSFUL` — the same defect class this project is about — so
`emptyModuleCheck` in the root build fails if that rule is broken.
