package com.locarya.domain.models

sealed trait AvailabilityReason:
  def message: String

object AvailabilityReason:
  case object StockDepleted extends AvailabilityReason:
    val message = "stock depleted"

  case object ComboItemMissing extends AvailabilityReason:
    val message = "combo item missing"

final case class UnavailableItem(itemId: ItemId, reason: AvailabilityReason)

final case class AvailabilityResult(
  available:        Boolean,
  unavailableItems: List[UnavailableItem]
)
