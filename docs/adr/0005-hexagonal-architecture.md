# ADR 0005: Hexagonal Architecture (Ports & Adapters)

**Status:** Accepted
**Date:** 2026-05-29
**Deciders:** Cleiton Queiroz
**Project:** Locarya

---

## Context

ADR #2 chose Scala 3 + Typelevel and sketched a package layout (`core / services / infrastructure / http / app`) plus a handful of patterns — Tagless Final, smart constructors, repository traits with `F[_]`. What it did **not** do is name the rule that holds those layers together: *which direction dependencies are allowed to point*. Without that rule written down, "layered packages" silently degrades into a distributed ball of mud where the HTTP layer reaches into the database and business rules leak into adapters.

The current codebase is at exactly the moment where this matters:

- **The domain core is already pure.** `com.locarya.core.domain` contains only entities, value objects and ADTs (`Provider`, `Booking`, `Money`, `TaxId`, `ValidationError`, …) with no `IO`, no doobie, no http4s. This is the inner hexagon and it is in good shape.
- **`com.locarya.services` is empty.** There is no application/use-case layer yet, and no ports (no `trait *Repository[F[_]]`, no `trait PaymentGateway[F[_]]`).
- **The only wired feature bypasses any port.** `HealthEndpoints` (an inbound HTTP adapter) calls `Database.checkHealth` (infrastructure) directly — `http → infrastructure`, with no port between them.

So the project is *shaped* like Hexagonal Architecture but does not yet *implement* it: it has the inner ring and a set of adapter-shaped folders, but no ports and no inversion of dependencies. The first real vertical slice (almost certainly Provider or Booking) will set the precedent for every slice after it. We want that precedent recorded before code, not reverse-engineered from it.

The domain itself justifies the rigor: Locarya has non-trivial business rules that must not be coupled to delivery mechanisms — temporal availability (stock per day, Combos consuming component Items), conditional Monitor requirements, split-payment policies via Asaas, conditional Contract generation. These rules belong in a core that can be tested and reasoned about independently of PostgreSQL, http4s, or Asaas.

---

## Decision

**Adopt Hexagonal Architecture (Ports & Adapters) as the organizing principle for the backend, with a single, enforced dependency rule:**

> **Dependencies point inward. The domain core depends on nothing; adapters depend on the core through ports. No inward layer may import an outward one.**

> **Package mapping superseded by [ADR 0006](0006-hexagonal-package-layout.md); the dependency rule below still holds.** The table immediately below records the *original* `core.domain / services / http / infrastructure / app` layout. The inward-dependency rule is unchanged and authoritative; only the package→hexagon mapping moved to ADR 0006 (`domain.models / domain.ports / domain.services / adapters.* / config / Main`).

The hexagon maps onto the existing packages as follows:

| Hexagon concept | Package | Contains | Effects? |
|---|---|---|---|
| **Domain core** (inner) | `com.locarya.core.domain` | Entities, value objects, ADTs, pure domain functions | ❌ Pure — no `F[_]`, no `IO` |
| **Application core / ports** | `com.locarya.services` | Use-case services (**inbound ports**) and the **outbound port** traits they depend on, all in `F[_]` | ⚠️ Abstract `F[_]` only — no concrete effect, no doobie/http4s |
| **Driving (inbound) adapters** | `com.locarya.http` | http4s routes/endpoints, DTOs, JSON codecs — translate HTTP → use-case calls | ✅ Concrete |
| **Driven (outbound) adapters** | `com.locarya.infrastructure` | doobie repositories, Asaas client, config, DB wiring — implement outbound ports | ✅ Concrete |
| **Composition root** | `com.locarya.app` | `Main` / `IOApp` — instantiates adapters, injects them into services, wires routes | ✅ Concrete |

Justification in one line: for a SaaS with rules that outlive any one delivery mechanism, **keeping business logic independent of IO, transport, and third parties is worth one indirection per dependency** — and Tagless Final makes that indirection the same `F[_]` abstraction ADR #2 already committed to.

---

## How the pieces fit

### Ports are `F[_]` traits; adapters implement them

- **Inbound port** — a use-case service trait, e.g. `trait BookingService[F[_]]` with `def create(...): F[Either[DomainError, Booking]]`. Lives in `services`. Implemented by `BookingServiceImpl` (also in `services`), which orchestrates pure domain logic and outbound ports.
- **Outbound port** — a dependency the core *needs* but does not *own*, e.g. `trait BookingRepository[F[_]]`, `trait PaymentGateway[F[_]]`, `trait ContractGenerator[F[_]]`, `trait Clock[F[_]]`. Declared in `services` (or a `services.ports` sub-package). Implemented by adapters in `infrastructure` (e.g. `DoobieBookingRepository`, `AsaasPaymentGateway`).

### Why ports live in `services`, not `core.domain`

CLAUDE.md and the current code keep `core.domain` strictly pure ("no IO, no Future, no effects"). A port trait references `F[_]`, so it cannot live there without breaking that invariant. Ports therefore belong to the **application core** (`services`): still effect-*abstract* (`F[_]`, never `IO`), but allowed to mention `F`. The dependency direction is preserved — `services` depends on `core.domain`, never the reverse.

### The composition root is the only place that knows concrete types

`Main` chooses `F = IO`, builds the doobie `Transactor`, instantiates `DoobieBookingRepository` and `AsaasPaymentGateway`, passes them into `BookingServiceImpl`, and hands the service to the http4s routes. Nothing inside `services` or `core.domain` ever names `IO`, PostgreSQL, or Asaas.

### Reference slice (Provider) — target shape

```
core.domain.Provider                     // pure entity + smart constructors (exists)
services.ProviderService[F]               // inbound port (use cases)
services.ProviderServiceImpl[F]           // orchestration: pure domain + outbound ports
services.ProviderRepository[F]            // outbound port
infrastructure.db.DoobieProviderRepository // driven adapter implementing the port
http.ProviderEndpoints[F]                 // driving adapter -> calls ProviderService
app.Main                                  // wires DoobieProviderRepository into ProviderServiceImpl
```

### Deliberate exception: health checks

`HealthEndpoints` calling `Database.checkHealth` directly is **allowed to stay as-is**. It carries no domain logic — it is an operational probe. Hexagonal discipline applies to *business* use cases, not to liveness/readiness plumbing. Forcing a port here would be ceremony with no payoff.

---

## Consequences

### Positive

- **Business rules are testable in isolation.** Services are tested against in-memory/stub port implementations (`F = IO` with a fake repo, or a pure `F`) — no Testcontainers needed for use-case logic. This directly cashes in the testability argument of ADR #2.
- **Adapters are swappable.** Asaas can be replaced, or PostgreSQL swapped, by writing a new outbound adapter — no change to `core.domain` or `services`.
- **The dependency rule is mechanically checkable.** Because the boundary is package + `F[_]`, a lint rule (or a simple "no `doobie`/`http4s`/`IO` import under `core.domain`/`services`" check in CI) can enforce it.
- **AI-navigability.** A clear, named convention means agents (and humans) know exactly where a new repository trait, adapter, or endpoint goes — fewer wrong-layer placements.

### Negative / Trade-offs accepted

- **Indirection cost.** Every external dependency gets a port trait + an adapter impl. For a CRUD-thin endpoint this is one extra file and a passthrough. **Accepted:** the cost is small and uniform, and it is the thing that keeps the core clean as rules accrete.
- **Risk of anemic services.** If logic leaks into adapters or services become thin passthroughs, we get the ceremony without the benefit. **Mitigation:** domain decisions (validation, availability, pricing, status transitions) live in `core.domain`; services only *orchestrate*.
- **Boilerplate via Tagless Final.** `F[_]` everywhere has a learning cost. **Mitigation:** already accepted in ADR #2; AI assistance generates the boilerplate.

### Neutral

- This does **not** mandate a multi-module sbt build. A single module with package boundaries is sufficient for the MVP; the rule can later be hardened into separate sbt modules (which makes illegal dependencies a *compile* error) if the team grows.

---

## Alternatives considered

**Plain layered / n-tier (rejected).** Controller → Service → Repository with dependencies pointing *down* toward the database. Simpler to start, but the domain ends up depending (transitively) on persistence concerns, and there is no inversion — exactly the `http → infrastructure` coupling already visible in `HealthEndpoints`. Fine for the health probe, wrong as the default for business logic.

**Clean Architecture / Onion (effectively chosen).** Hexagonal, Clean, and Onion share the same dependency-inversion core. We adopt the **Hexagonal vocabulary** (ports & adapters, driving/driven) because it names the two *kinds* of boundary explicitly, which maps cleanly onto Tagless Final `F[_]` traits and onto the inbound (`http`) vs outbound (`infrastructure`) split we already have. No practical difference in the resulting code.

**No formal architecture / keep going feature-by-feature (rejected).** Cheapest today, most expensive later. The first few slices would set an accidental precedent and the pure-core property we currently have for free would erode. Recording the rule now costs one ADR.

**Multi-module sbt enforcement up front (deferred, not rejected).** Splitting `core` / `services` / `infrastructure` into separate sbt modules turns the dependency rule into a compiler-enforced guarantee. Deferred as premature for a 1-dev MVP; revisit if illegal-dependency drift appears or the team grows.

---

## Relationship to existing decisions

- **ADR #2 (Scala + Typelevel):** This ADR makes the *structural* half of #2 explicit. #2 listed the packages and patterns (Tagless Final, repository traits, ADTs); #5 states the dependency rule that gives them meaning. Tagless Final `F[_]` is the concrete mechanism for ports.
- **CLAUDE.md domain conventions:** Reinforced, not changed. `core.domain` stays pure; smart-constructor and `*Id` conventions are unaffected. The new guidance is solely about *where ports live* and *which way dependencies point*.

---

## Implementation notes

1. Establish the pattern with **one vertical slice** (Provider): port → adapter → service → endpoint → wired in `Main`, with service-level tests against a stub repository.
2. Add a CI guard: fail the build if anything under `com.locarya.core.domain` or `com.locarya.services` imports `doobie`, `org.http4s`, or `cats.effect.IO` (abstract `F[_]` is allowed; concrete `IO` is not).
3. Update CLAUDE.md's *Architecture* section to point at this ADR and document the package→hexagon mapping for future contributors and agents.
4. Leave `HealthEndpoints` as the documented exception.

---

## References

- Alistair Cockburn — *Hexagonal Architecture (Ports & Adapters)*
- ADR #2 — Scala + Typelevel stack (package layout, Tagless Final, repository pattern)
- ADR #1 — Split payment via Asaas (the canonical outbound port: `PaymentGateway[F[_]]`)
- *Functional and Reactive Domain Modeling* — Debasish Ghosh (Tagless Final as ports in Scala)
