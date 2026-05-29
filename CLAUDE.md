# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Locarya API** project - currently in initial setup phase.

## Development Commands

```bash
sbt compile          # compile
sbt test             # run all tests (unit + integration via Testcontainers)
sbt run              # start server
sbt flywayMigrate    # apply DB migrations
```

## Architecture

### Stack

- **Backend:** Scala 3 + Typelevel (Cats Effect 3, http4s, doobie, circe)
- **Payment Gateway:** Asaas (split payment)
- **Pattern:** Tagless Final, ADTs, IO monad

Ver `docs/adr/0002-scala-typelevel-stack.md` para justificativa completa da escolha de stack.

### Layering — Hexagonal (Ports & Adapters)

The backend follows Hexagonal Architecture. **Dependency rule: dependencies point inward — the domain core depends on nothing; adapters depend on the core through ports. No inward layer imports an outward one.** See `docs/adr/0005-hexagonal-architecture.md`.

| Hexagon role | Package | Effects? |
|---|---|---|
| Domain core (entities, value objects, ADTs, pure logic) | `com.locarya.core.domain` | ❌ Pure — no `F[_]`, no `IO` |
| Application core — use-case services (**inbound ports**) + the **outbound port** traits they need | `com.locarya.services` | ⚠️ Abstract `F[_]` only — never concrete `IO`, no doobie/http4s |
| Driving (inbound) adapters — http4s routes, DTOs, JSON codecs | `com.locarya.http` | ✅ Concrete |
| Driven (outbound) adapters — doobie repos, Asaas client, config, DB | `com.locarya.infrastructure` | ✅ Concrete |
| Composition root — instantiates adapters, injects into services, wires routes | `com.locarya.app` | ✅ Concrete |

- **Ports are `F[_]` traits and live in `services`, never in `core.domain`** (a port mentions `F[_]`; the domain core must stay effect-free). Inbound port = the use-case service trait (e.g. `trait BookingService[F[_]]`); outbound port = a dependency the core needs but doesn't own (e.g. `trait BookingRepository[F[_]]`, `trait PaymentGateway[F[_]]`).
- **Only `app.Main` names concrete types** (`IO`, the doobie `Transactor`, the Asaas client). `core.domain` and `services` must not import `doobie`, `org.http4s`, or `cats.effect.IO`.
- **Exception:** `HealthEndpoints` calling `Database` directly is allowed — it's an operational probe with no domain logic. Hexagonal discipline applies to *business* use cases.

### Domain Language

O domínio está completamente documentado em `CONTEXT.md` no root do repositório. Leia antes de trabalhar no código — define termos canônicos como Locador, Cliente, Reserva, Item, Combo, Pagamento, Monitor, etc.

Decisões arquiteturais importantes estão em `docs/adr/`:
- **ADR #1:** Split payment direto via Asaas (vs custódia na plataforma)
- **ADR #2:** Scala + Typelevel (vs Node.js/Java/Kotlin)
- **ADR #3:** Observability & Structured Logging (log4cats + JSON, correlation tracking)
- **ADR #4:** Support for individual providers (CPF or CNPJ, exactly one)
- **ADR #5:** Hexagonal Architecture (Ports & Adapters) — dependency rule and package→hexagon mapping

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses default triage label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository with CONTEXT.md and docs/adr/ at the root. See `docs/agents/domain.md`.

## Domain Coding Conventions

All domain code lives in `com.locarya.core.domain`. The layer is **pure** — no IO, no Future, no effects.

### Value object construction

Private constructors force all construction through smart constructors:

```scala
final case class Foo private (value: String)
```

Two naming conventions are in use — follow the one that matches the type:

| Pattern | Used by | Returns |
|---|---|---|
| `create(field1, field2, ...)` | multi-field types: `Address`, `TaxId` | `Either[ValidationError, T]` |
| `fromString(raw: String)` | string-parsed types: `CPF`, `CNPJ`, `Email`, all `*Id` types | `Either[ValidationError, T]` |
| `fromAmount(amount: BigDecimal)` | `Money` | `Either[ValidationError, Money]` |

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

- Framework: **munit** (`org.scalameta:munit`) + **munit-cats-effect** for IO tests
- Integration tests use **Testcontainers** (PostgreSQL) — they run in `sbt test` automatically
- Test files mirror source paths: `AddressSpec` → `src/test/scala/com/locarya/core/domain/AddressSpec.scala`
- Cover: valid happy path, each validation rule's rejection, and boundary/edge values

## Notes

- When adding code, update this file with relevant build commands, test procedures, and architectural patterns
