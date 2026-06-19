# ADR 0008 — Soft-Delete Policy

**Status:** Accepted  
**Date:** 2026-06-19

## Context

The domain contains entities that accumulate financial and audit history through `Booking` and `Payment` records. Hard-deleting a `Provider`, `Item`, `Combo`, or `Attendant` that has associated bookings would orphan that history and break audit trails. The platform also needs a way to hide inactive items from the Loja without destroying their data.

## Decision

All four core entities carry an `is_active BOOLEAN NOT NULL DEFAULT TRUE` column. Removal is always a logical deactivation (`is_active = false`), never a physical `DELETE`.

**Scope:**

| Entity | `is_active` added | Migration |
|--------|-------------------|-----------|
| `items` | V4 | `V4__Add_item_is_active.sql` |
| `combos` | V5 | `V5__Add_combo_is_active.sql` |
| `attendants` | V6 | `V6__Add_attendant_is_active.sql` |
| `providers` | V8 | `V8__Add_provider_is_active.sql` |

**Rules:**

1. `DELETE` statements are forbidden for any entity that can have associated bookings (`items`, `combos`, `attendants`, `providers`). Attempting one is a bug, not a valid operation.
2. Deactivation is expressed via the domain model's `.deactivate` extension, which returns a copy with `isActive = false`. The service layer calls `.deactivate` and then `repository.update(deactivated)`.
3. The constraint is enforced at the **service layer**, not the database layer. No `ON DELETE` triggers or DB-level restrictions exist in MVP — correctness relies on service code never issuing raw deletes.
4. Active-only reads use filtered queries (e.g., `WHERE is_active = TRUE`) in the repository adapter. Each table has an index on `is_active` to support these queries.
5. `providers.is_active` is a data-consistency flag only in MVP. No cascading deactivation of owned items or combos is implemented at this stage.

## Domain model

Each entity has:
- A plain `isActive: Boolean` field (no effect type — pure domain).
- A `create(...)` smart constructor with `isActive: Boolean = true` as the final defaulted parameter.
- A `.deactivate` extension in the companion object: `entity.copy(isActive = false)`.

## Consequences

- Audit and financial history is always preserved, satisfying accounting and compliance needs.
- The Loja can filter active items/combos by reading only `WHERE is_active = TRUE`.
- Database storage grows monotonically — this is acceptable for MVP scale.
- A future cleanup/archival process (out of scope for MVP) could physically remove records that have been inactive and have no recent bookings.
