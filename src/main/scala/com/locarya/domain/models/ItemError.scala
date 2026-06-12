package com.locarya.domain.models

sealed abstract class ItemError(message: String) extends RuntimeException(message)

object ItemError:
  final case class InvalidInput(error: ValidationError)
      extends ItemError(error.toString)

  final case class HasBookings(itemId: ItemId)
      extends ItemError(s"Item ${itemId.value} has existing bookings and cannot be deactivated")

  final case class NotFound(itemId: ItemId)
      extends ItemError(s"Item ${itemId.value} not found")

  final case class Forbidden(itemId: ItemId)
      extends ItemError(s"Provider does not own item ${itemId.value}")
