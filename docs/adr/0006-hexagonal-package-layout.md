# ADR 0006: Hexagonal Package Layout

**Status:** Accepted
**Date:** 2026-06-08
**Deciders:** Cleiton Queiroz
**Project:** Locarya
**Supersedes:** the package→hexagon mapping table of [ADR 0005](0005-hexagonal-architecture.md) (only the mapping; 0005's inward-dependency rule still holds)

---

## Context

[ADR 0005](0005-hexagonal-architecture.md) adopted Hexagonal Architecture and stated the rule that matters — **dependencies point inward; no inner layer imports an outer one** — but it mapped the hexagon onto the *inherited* package names (`core.domain`, `services`, `http`, `infrastructure`, `app`). Those names carried two problems:

- **Role ambiguity.** `infrastructure` lumped persistence, config, and (eventually) external clients together; `http` did not read as "an adapter"; `services` did not say whether it held ports, implementations, or both. A newcomer (human or agent) could not infer the hexagon role from the package name.
- **Ports had no home of their own.** 0005 put both inbound and outbound port traits in `services` "or a `services.ports` sub-package" — left undecided. Without a fixed location, port traits and their implementations blur together.

This ADR fixes the *names and locations* so the hexagon role is legible from the package path, and so the dependency-rule guard has stable directories to check. It does **not** change the dependency rule, the use of Tagless Final `F[_]` for ports, or the pure-core invariant — all of that is inherited from 0005 unchanged.

---

## Decision

**Adopt the following package layout. The hexagon role is encoded in the package path.**

| Hexagon role | Package | Contains | Effects? |
|---|---|---|---|
| **Domain core** (inner) | `com.locarya.domain.models` | Entities, value objects, ADTs, pure domain functions | ❌ Pure — no `F[_]`, no `IO`, no `cats.effect` |
| **Ports** (inbound + outbound) | `com.locarya.domain.ports` | Use-case service traits (**inbound ports**) and the outbound port traits they depend on, all in `F[_]` | ⚠️ Abstract `F[_]` only — no concrete effect, no doobie/http4s |
| **Use-case services** | `com.locarya.domain.services` | Service implementations that orchestrate pure domain logic over outbound ports | ⚠️ Abstract `F[_]` only |
| **Driving (inbound) adapter** | `com.locarya.adapters.http` | http4s routes/endpoints, DTOs, JSON codecs, middleware | ✅ Concrete |
| **Driven (outbound) adapter — persistence** | `com.locarya.adapters.persistence` | doobie repositories, DB wiring, the `Database` probe | ✅ Concrete |
| **Driven (outbound) adapter — external** | `com.locarya.adapters.external` | Third-party clients (e.g. Asaas split-payment) implementing outbound ports | ✅ Concrete |
| **Configuration** | `com.locarya.config` | `AppConfig` and config loading | ✅ Concrete |
| **Composition root** | `com.locarya.Main` | `IOApp` — instantiates adapters, injects them into services, wires routes | ✅ Concrete |

Target tree:

```
com/locarya/
  Main.scala                        // composition root — the only place that names IO
  config/                           // AppConfig
  domain/
    models/                         // pure: entities, value objects, ADTs
    ports/                          // inbound + outbound port traits (F[_])
    services/                       // use-case service implementations (F[_])
  adapters/
    http/                           // driving adapter: routes, DTOs, codecs, middleware/
    persistence/                    // driven adapter: doobie repos, Database
    external/                       // driven adapter: Asaas & other third-party clients
```

### Notable placement decisions

- **`domain.models` is strictly pure — not even `F[_]`.** It must not import `doobie`, `org.http4s`, or *any* `cats.effect`. This is a tier stricter than `ports`/`services`, and the guard (`scripts/check-architecture.sh`) enforces it.
- **Ports live in `domain.ports`, not `domain.models`.** A port mentions `F[_]`, so it cannot live in the strictly-pure model layer. It still depends only on `domain.models`, never on an adapter — the inward rule holds.
- **`config` and `Main` sit at the `com.locarya` root**, outside both `domain` and `adapters`. They are concrete wiring, not part of the hexagon's inner rings, and are excluded from coverage (see [ADR 0007](0007-testing-strategy.md)).
- **The `HealthEndpoints → Database` exception from ADR 0005 is preserved**, now expressed as `adapters.http → adapters.persistence` (adapter-to-adapter, no domain logic).

---

## Reference slice (Provider) — target shape with new paths

```
com.locarya.domain.models.Provider                       // pure entity + smart constructors (exists)
com.locarya.domain.ports.ProviderService[F]              // inbound port (use cases)
com.locarya.domain.ports.ProviderRepository[F]           // outbound port
com.locarya.domain.services.ProviderServiceImpl[F]       // orchestration: pure domain + outbound ports
com.locarya.adapters.persistence.DoobieProviderRepository // driven adapter implementing the port
com.locarya.adapters.http.ProviderEndpoints[F]           // driving adapter -> calls ProviderService
com.locarya.Main                                         // wires DoobieProviderRepository into ProviderServiceImpl
```

---

## Consequences

### Positive

- **Role is legible from the path.** `adapters.persistence.DoobieProviderRepository` vs `domain.ports.ProviderRepository` makes the port/adapter split obvious to humans and agents — fewer wrong-layer placements.
- **The guard has stable, role-named directories.** `check-architecture.sh` guards `domain.models` (strictly pure) and `domain.ports`/`domain.services` (abstract `F[_]`) by path.
- **Ports have a single home** (`domain.ports`), resolving the "or a sub-package" ambiguity 0005 left open.

### Negative / Trade-offs accepted

- **One-time import churn.** Every file's `package`/`import` changes. **Accepted:** a single mechanical refactor with no behavior change.
- **Two effect tiers to remember** (`models` = no effects at all; `ports`/`services` = abstract `F[_]`). **Mitigation:** the guard enforces both; the table above documents them.

### Neutral

- Still a single sbt module; the rule remains lint-enforced, not compiler-enforced. Multi-module enforcement stays deferred, exactly as in 0005.

---

## Relationship to existing decisions

- **ADR 0005 (Hexagonal Architecture):** This ADR supersedes *only* 0005's package→hexagon mapping table. The inward-dependency rule, the ports-are-`F[_]`-traits decision, the composition-root-knows-concrete-types rule, and the health-check exception are all inherited unchanged.
- **ADR 0007 (Testing strategy):** Builds on this layout — coverage exclusions are expressed in terms of these package names (`adapters.*`, `config`, `Main`).

---

## References

- [ADR 0005](0005-hexagonal-architecture.md) — Hexagonal Architecture (the dependency rule this layout serves)
- [ADR 0007](0007-testing-strategy.md) — Testing strategy (in-memory port impls; coverage exclusions)
- Alistair Cockburn — *Hexagonal Architecture (Ports & Adapters)*
