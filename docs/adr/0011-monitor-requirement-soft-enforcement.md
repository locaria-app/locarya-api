# ADR 0011 — Monitor requirement: boolean flag with soft enforcement at confirmation

**Status:** Accepted
**Date:** 2026-07-08

## Context

`AttendantRequirement` (the Monitor requirement flag on `Item`/`Combo`) originally had three states — `Required`, `Optional`, `NotAllowed` — mirrored in `CONTEXT.md` as "obrigatório/opcional/não permite". In practice only `Required` had any enforcement, and that enforcement was a **hard block**: `BookingServiceImpl.requireAttendantsWhenConfirming` raised an error and prevented confirming a Booking that had a `Required` item with no Attendant assigned to it. `NotAllowed` had zero enforcement anywhere in the backend — nothing stopped assigning an Attendant to a booking whose items were all `NotAllowed`. Attendant assignment itself was booking-level (`booking_attendants(booking_id, attendant_id)`), with no link to which item within the booking the Attendant was for.

Talking through this with the Provider persona surfaced two problems with the original design:

1. **Wrong default posture.** Monitors are typically freelancers/hourly workers who confirm availability last-minute — often the day of the event. A hard block on confirmation forces the Provider to either delay confirming a booking they've already verbally agreed to, or leave it in limbo waiting for a Monitor who may only be findable hours before delivery.
2. **`NotAllowed` solved a problem nobody had.** The Provider wants the flexibility to attach a Monitor to *any* item regardless of the flag — the flag should only express "the Provider is warned if this is missing," not "the platform refuses to let you do this."

## Decision

- `AttendantRequirement` collapses from three states to a boolean: **does this Item/Combo require a Monitor?** `NotAllowed` is removed as a concept — every Item/Combo permits a Monitor to be associated, regardless of the flag.
- Enforcement moves from hard block to **soft warning with explicit override**. Confirming a Booking that has a "requires Monitor" line with no Attendant assigned returns a structured error listing the affected lines instead of silently succeeding. The Provider resubmits the same confirmation call with an explicit acknowledgment flag to force it through.
- The Booking records that it was confirmed with a missing Monitor on a line that required one — this is a deliberate, auditable decision by the Provider, not silent data loss. It also gives a future Contrato-generation feature a signal to flag on the document.
- Attendant assignment moves from booking-level to **booking-item-level**. A `BookedIndividualItem` or `BookedCombo` line gets its own Attendant associations, not the Booking as a whole. A Combo is still treated as a single line for this purpose — if a Combo requires a Monitor because one of its component Items does, the Monitor is associated to the Combo line, not to the individual component Item inside it (consistent with how Combos are already treated as a unit for stock and pricing).

## Consequences

- `booking_attendants` needs a booking-item reference (not just `booking_id`) — a schema migration, not just a domain-model change.
- `requireAttendantsWhenConfirming` becomes two-phase: an unforced call that reports missing Monitors per line without persisting the status change, and a forced call (via the acknowledgment flag) that persists it and records the risk was taken.
- `ComboServiceImpl.inferAttendantRequirement`'s three-branch logic collapses to a boolean OR across component items.
- The existing singular `Booking.attendantId: Option[AttendantId]` field predates the `booking_attendants` join table and was already unused by the assignment flow — it should be removed as part of this work, not carried forward as a second source of truth.
- Contrato generation (not yet implemented) can later read the "confirmed without Monitor" flag to surface it on the generated document — no contract logic is being built now, this just avoids a future rename.
