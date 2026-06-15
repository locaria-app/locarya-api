package com.locarya.domain.models

case class UnavailableItem(itemId: ItemId, reason: String)
case class AvailabilityResult(available: Boolean, unavailableItems: List[UnavailableItem])
