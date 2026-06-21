# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Locarya API** project - currently in initial setup phase.

## Development Commands

```bash
sbt compile                              # compile
sbt test                                 # run all tests (in-memory port impls + stubs — no Docker/Testcontainers)
sbt clean coverage test coverageReport   # tests with scoverage report (adapters/config/Main excluded — see ADR 0007)
sbt run                                  # start server
sbt flywayMigrate                        # apply DB migrations
```

## Architecture

### Stack

- **Backend:** Scala 3 + Typelevel (Cats Effect 3, http4s, doobie, circe)
- **Payment Gateway:** Asaas (split payment)
- **Pattern:** Tagless Final, ADTs, IO monad

Ver `docs/adr/0002-scala-typelevel-stack.md` para justificativa completa da escolha de stack.

### Layering — Hexagonal (Ports & Adapters)

The backend follows Hexagonal Architecture. **Dependency rule: dependencies point inward — the domain core depends on nothing; adapters depend on the core through ports. No inward layer imports an outward one.** The rule is in `docs/adr/0005-hexagonal-architecture.md`; the package layout below is in `docs/adr/0006-hexagonal-package-layout.md`.

| Hexagon role                                                                                             | Package                                                              | Effects?                                                        |
| -------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------- |
| Domain core (entities, value objects, ADTs, pure logic)                                                  | `com.locarya.domain.models`                                          | ❌ Pure — no `F[_]`, no `IO`, no `cats.effect`                  |
| Ports — use-case service traits (**inbound ports**) + the **outbound port** traits they need             | `com.locarya.domain.ports`                                           | ⚠️ Abstract `F[_]` only — never concrete `IO`, no doobie/http4s |
| Use-case services — implementations orchestrating domain logic over outbound ports                       | `com.locarya.domain.services`                                        | ⚠️ Abstract `F[_]` only                                         |
| Driving (inbound) adapter — http4s routes, DTOs, JSON codecs, middleware                                 | `com.locarya.adapters.http`                                          | ✅ Concrete                                                     |
| Driven (outbound) adapters — doobie repos & DB (`persistence`), Asaas & third-party clients (`external`) | `com.locarya.adapters.persistence` / `com.locarya.adapters.external` | ✅ Concrete                                                     |
| Configuration                                                                                            | `com.locarya.config`                                                 | ✅ Concrete                                                     |
| Composition root — instantiates adapters, injects into services, wires routes                            | `com.locarya.Main`                                                   | ✅ Concrete                                                     |

- **Ports are `F[_]` traits and live in `domain.ports`, never in `domain.models`** (a port mentions `F[_]`; the domain model layer must stay effect-free). Inbound port = the use-case service trait (e.g. `trait BookingService[F[_]]`); outbound port = a dependency the core needs but doesn't own (e.g. `trait BookingRepository[F[_]]`, `trait PaymentGateway[F[_]]`). Use-case service implementations live in `domain.services`.
- **Only `Main` names concrete types** (`IO`, the doobie `Transactor`, the Asaas client). `domain.models`, `domain.ports`, and `domain.services` must not import `doobie`, `org.http4s`, or `cats.effect.IO`; `domain.models` must not import `cats.effect` at all. Enforced by `scripts/check-architecture.sh`.
- **Exception:** `HealthEndpoints` (`adapters.http`) calling `Database` (`adapters.persistence`) directly is allowed — it's an operational probe with no domain logic. Hexagonal discipline applies to _business_ use cases.

## Code organization conventions

- **One trait per file** in `domain.ports` and `domain.services`. File name matches
  the trait (`ProviderRepository.scala`, not `package.scala` with multiple traits).
  Rationale: code review friction, parallel work, mirrors the trio-coffeeshop layout.
- **Do not use `package.scala`** — it's a Scala 2 vestige for `package object`. In
  Scala 3, declare `package com.locarya.domain.ports` at the top of any `.scala` file.
- **Shared abstractions** (e.g. base `Repository[F, E, ID]`) go in their own file
  (`Repository.scala`) when reused, not inlined into one of the concrete traits.

## Repository design decisions (applies to every repo trait)

- **`create` semantics:** raise in `F` on duplicate-id / unique-constraint violation
  (not `F[Either[DomainError, E]]`). Callers handle via `MonadError`. Keeps the
  port surface minimal; doobie adapter translates SQL state to a domain error type.
- **`findByDateRange` overlap:** date-inclusive on both ends —
  `booking.date >= start && booking.date <= end` (single-date bookings in MVP per PRD).
- **Base `Repository[F, E, ID]` trait:** structural marker with `create`, `findById`,
  `update`. The five concrete traits extend it but add domain-specific methods freely.
  Not a forced abstraction — soft-delete and other ops go on the concrete trait.
- **No `delete` method on repositories** in the MVP — soft-delete via `is_active`
  is implemented as a domain operation in services + issue #31. Repos expose `update`.
- **Testcontainers:** deferred per ADR #7. `sbt test` runs without Docker; repository
  correctness is verified via in-memory port impls in `src/test/scala/com/locarya/helpers/`.

### Domain Language

O domínio está completamente documentado em `CONTEXT.md` no root do repositório. Leia antes de trabalhar no código — define termos canônicos como Locador, Cliente, Reserva, Item, Combo, Pagamento, Monitor, etc.

Decisões arquiteturais importantes estão em `docs/adr/`:

- **ADR #1:** Split payment direto via Asaas (vs custódia na plataforma)
- **ADR #2:** Scala + Typelevel (vs Node.js/Java/Kotlin)
- **ADR #3:** Observability & Structured Logging (log4cats + JSON, correlation tracking)
- **ADR #4:** Support for individual providers (CPF or CNPJ, exactly one)
- **ADR #5:** Hexagonal Architecture (Ports & Adapters) — the inward dependency rule
- **ADR #6:** Hexagonal package layout — `domain.{models,ports,services}` / `adapters.*` / `config` / `Main` (supersedes #5's package mapping)
- **ADR #7:** Testing strategy — in-memory port impls + stub gateways, no Testcontainers in `sbt test`, coverage exclusions

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses default triage label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository with CONTEXT.md and docs/adr/ at the root. See `docs/agents/domain.md`.

## Domain Coding Conventions

All domain model code lives in `com.locarya.domain.models`. The layer is **pure** — no `F[_]`, no `IO`, no `cats.effect`, no Future, no effects. Port traits (abstract `F[_]`) live in `com.locarya.domain.ports`; use-case service implementations (abstract `F[_]`) live in `com.locarya.domain.services`. None of the three may import `doobie`, `org.http4s`, or `cats.effect.IO`.

### Value object construction

Private constructors force all construction through smart constructors:

```scala
final case class Foo private (value: String)
```

Two naming conventions are in use — follow the one that matches the type:

| Pattern                          | Used by                                                      | Returns                          |
| -------------------------------- | ------------------------------------------------------------ | -------------------------------- |
| `create(field1, field2, ...)`    | multi-field types: `Address`, `TaxId`                        | `Either[ValidationError, T]`     |
| `fromString(raw: String)`        | string-parsed types: `CPF`, `CNPJ`, `Email`, all `*Id` types | `Either[ValidationError, T]`     |
| `fromAmount(amount: BigDecimal)` | `Money`                                                      | `Either[ValidationError, Money]` |

New value objects must follow the same pattern. Pick `create` for multi-field, `fromString` for single-string input.

### Entity IDs

Each aggregate root has a dedicated ID type backed by a UUID string — **not** Scala opaque types, but `final case class` with a private constructor:

```scala
final case class ProviderId private (value: String)
object ProviderId:
  def generate: ProviderId = ProviderId(UUID.randomUUID().toString)
  def fromString(id: String): Either[ValidationError, ProviderId] = ...
```

Pattern: `generate` for new IDs, `fromString` for deserialized IDs (validates UUID format).

### ValidationError ADT

```scala
sealed trait ValidationError
case class InvalidAddress(message: String) extends ValidationError
// one variant per domain concept — always carries a human-readable message
```

Add a new `case class Invalid*(message: String)` variant for each new domain type. Never reuse a variant across unrelated types.

### TaxId

`TaxId` is a sealed trait with two inner case classes: `TaxId.CPFTaxId(cpf: CPF)` and `TaxId.CNPJTaxId(cnpj: CNPJ)`. Build via `TaxId.create(cpfOpt, cnpjOpt)` (exactly one of the two must be `Some`). Convenience constructors `fromCPF` / `fromCNPJ` are available when the type is already known.

### Testing conventions

See `docs/adr/0007-testing-strategy.md`.

- Framework: **munit** (`org.scalameta:munit` 1.x) + **munit-cats-effect** (2.x) for IO tests
- **No Testcontainers / no Docker in `sbt test`.** Service, route, and use-case tests run against **in-memory implementations of the port traits** and **stub gateways** — shared helpers live in `src/test/scala/com/locarya/helpers/`. Domain-model specs construct values and assert on smart-constructor results directly.
- Coverage: `sbt clean coverage test coverageReport`. Excluded packages (thin wiring, covered by the deferred integration suite): `com.locarya.adapters.*`, `com.locarya.config.*`, `com.locarya.Main` — see `coverageExcludedPackages` in `build.sbt`.
- A **minimal real-DB integration suite (Testcontainers)** is deferred to a single end-of-backlog tech-debt issue — it is _not_ part of the default test path.
- Test files mirror source paths: `AddressSpec` → `src/test/scala/com/locarya/domain/models/AddressSpec.scala`
- Cover: valid happy path, each validation rule's rejection, and boundary/edge values

## HTTP routing conventions (Tapir)

All HTTP endpoints are defined with **Tapir** (`sttp.tapir`). The two base endpoints in `TapirSupport` are the **only** allowed starting points — never use raw `endpoint.*` directly:

| Base | Use for | What it provides |
|---|---|---|
| `publicBase` | Unauthenticated routes (auth, storefront, availability, booking) | `/api/v1` path prefix |
| `securedBase` | JWT-authenticated routes (dashboard, items, combos, …) | `/api/v1` prefix + bearer security + unified `ErrorBody` error output |

```scala
// Public endpoint — add your own errorOut
private val myE = publicBase.get
  .in("storefront" / path[String]("slug"))
  .out(jsonBody[MyResponse])
  .errorOut(statusCode.and(jsonBody[ErrorBody]))

// Secured endpoint — error output is inherited from securedBase
private val myE = securedBase
  .in("dashboard" / "items")
  .get
  ...
```

**Rules enforced by convention:**
- The `/api/v1` prefix lives in `TapirSupport`, **not** in `Router(...)` mounting in `Main`. Never wrap Tapir routes in `Router("/api/v1" -> ...)`.
- Every `routes[F](...)` implementation must be composed into the route chain in `Main.scala` in the **same PR** that defines `allEndpoints`. If `allEndpoints` appears in the OpenAPI docs wiring, the routes must be served.
- Tests call route handlers directly (no `Router` wrapper). Because the prefix is already in the endpoint path, test URIs must include `/api/v1/...`.
- For endpoints that need a **different error body per status code** (e.g., a structured 409 with item-level detail), use Tapir `oneOf` with a private sealed trait instead of the shared `ErrorBody`. See `StorefrontBookingRoutes` for the pattern.

## Notes

- When adding code, update this file with relevant build commands, test procedures, and architectural patterns
