package com.locarya.domain.models

final case class UnavailableItem(itemId: ItemId, reason: String)

final case class AvailabilityResult(
  available: Boolean,
  unavailableItems: List[UnavailableItem]
)

object AvailabilityResult:
  val available: AvailabilityResult = AvailabilityResult(true, List.empty)

  def unavailable(items: List[UnavailableItem]): AvailabilityResult =
    AvailabilityResult(false, items)
