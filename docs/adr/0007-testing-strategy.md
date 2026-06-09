# ADR 0007: Testing Strategy

**Status:** Accepted
**Date:** 2026-06-08
**Deciders:** Cleiton Queiroz
**Project:** Locarya

---

## Context

The original test setup wired the default `sbt test` path to a real PostgreSQL: it pulled in `testcontainers-scala-munit` / `testcontainers-scala-postgresql`, and `MigrationSpec` spun up a container to exercise Flyway migrations. That has two costs we no longer want to pay on every run:

- **Docker is required to test.** Anyone (or any CI job, or any agent) running `sbt test` needed a Docker daemon and a several-hundred-MB Postgres image pull. The architecture chosen in [ADR 0005](0005-hexagonal-architecture.md) / [ADR 0006](0006-hexagonal-package-layout.md) exists precisely so business logic can be tested *without* infrastructure — the test path should cash that in.
- **Slow, flaky feedback for logic that has no database in it.** Domain models and use-case services are pure or effect-abstract; gating them behind a container couples fast tests to slow infrastructure.

Hexagonal Architecture gives us the seam to do better: ports are `F[_]` traits, so a use case can run against an **in-memory implementation** of its outbound ports instead of a real adapter.

This mirrors the approach proven in the **trio-coffeeshop** challenge, where services were tested against in-memory repositories and stub gateways and the real-DB suite was kept separate and minimal.

---

## Decision

**The default test path (`sbt test`) runs entirely against in-memory port implementations and stub gateways — no Testcontainers, no Docker, no real database.**

1. **In-memory port implementations + stubs.** Service, route, and use-case tests run against in-memory implementations of the outbound port traits (e.g. a `Ref`-backed `ProviderRepository[F]`) and stub gateways (e.g. a canned `PaymentGateway[F]`). These helpers live in **`src/test/scala/com/locarya/helpers/`** and are shared across specs.

2. **Domain models are tested directly.** `com.locarya.domain.models.*` is pure, so its specs construct values and assert on smart-constructor results — no effects, no ports.

3. **Coverage measures the core, not the wiring.** Coverage is collected via scoverage; the following are **excluded** because they are thin wiring exercised by the deferred integration suite, not by unit tests:
   - `com.locarya.adapters.*` — driving (http) and driven (persistence, external) adapters
   - `com.locarya.config.*` — configuration loading
   - `com.locarya.Main` — the composition root

   Encoded in `build.sbt`:
   ```
   coverageExcludedPackages := "com\\.locarya\\.adapters\\..*;com\\.locarya\\.Main;com\\.locarya\\.config\\..*"
   ```
   Run with: `sbt clean coverage test coverageReport`.

4. **No Testcontainers in the default path.** The `testcontainers-scala-*` dependencies are removed from `build.sbt`; `MigrationSpec` (which required a Postgres container) is deleted. CI runs `sbt clean coverage test coverageReport` with **no** database service.

5. **A single, deferred real-DB integration suite covers what unit tests cannot.** Exactly one end-of-backlog, lowest-priority tech-debt issue reintroduces a *minimal* Testcontainers suite covering Flyway migrations (the V1/V2 schema + constraints that `MigrationSpec` used to assert) and 1–2 repository round-trips. It does not gate day-to-day development.

---

## Consequences

### Positive

- **`sbt test` runs anywhere, fast, with no Docker.** Contributors and agents get green/red feedback on domain and use-case logic without infrastructure.
- **Coverage reflects logic we actually author.** Excluding adapters/config/Main keeps the coverage signal focused on the core, where bugs are expensive.
- **The seam is exercised, not bypassed.** Testing through ports validates that the use case truly depends only on its ports — a design check, not just a correctness check.

### Negative / Trade-offs accepted

- **Adapters are not unit-covered by default.** A doobie repository or the Asaas client could pass `sbt test` while broken against real infrastructure. **Mitigation:** the deferred integration suite (item 5) covers migrations and representative round-trips; until it lands, adapters are covered only by manual/compile checks. **Accepted** because adapters are thin and the integration suite is explicitly scheduled.
- **In-memory fakes can drift from real adapter behavior.** A `Ref`-backed repo may not reproduce, e.g., a unique-constraint violation. **Mitigation:** the integration suite asserts the constraints; fakes model the contract the port promises, not the storage engine.

### Neutral

- This does not forbid integration tests — it scopes them to one minimal, deliberately-separate suite rather than the default path.

---

## Relationship to existing decisions

- **ADR 0005 / ADR 0006:** This strategy is the payoff of the hexagon. Ports-as-`F[_]` (0005) and the role-named packages (0006) are what make in-memory testing and the coverage exclusions expressible.
- **ADR 0003 (Observability):** Logging is verified by `LoggingSpec` (log4cats emits without error); structured-JSON output is confirmed via manual/integration inspection, consistent with this split.

---

## References

- [ADR 0005](0005-hexagonal-architecture.md) — Hexagonal Architecture (ports are `F[_]` traits → in-memory testable)
- [ADR 0006](0006-hexagonal-package-layout.md) — Package layout (the names the coverage exclusions reference)
- trio-coffeeshop challenge — in-memory repositories + stub gateways with a separate minimal real-DB suite (prior art)
