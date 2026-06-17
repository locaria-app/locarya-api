package com.locarya.domain.models

enum AvailabilityKind:
  case Item, Combo, Unknown

/** Availability of a single requested id (Item or Combo) on a given date.
  *
  *   - `availableQty` — for an Item, the remaining stock (`stock - consumed`, never negative);
  *     for a Combo, the number of full combos that can still be fulfilled (the min over its
  *     constituent items of `remaining / perComboQty`).
  *   - `available` — `availableQty >= requestedQty`. A Combo can be unavailable while its
  *     constituent items are independently available.
  *   - `Unknown` kind is used when the requested id matches neither an Item nor a Combo.
  */
final case class ItemAvailability(
  id:           ItemId,
  kind:         AvailabilityKind,
  available:    Boolean,
  availableQty: Int
)
