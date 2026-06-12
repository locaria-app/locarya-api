package com.locarya.domain.models

sealed abstract class ComboError(message: String) extends RuntimeException(message)

object ComboError:
  final case class InvalidInput(error: ValidationError)
      extends ComboError(error.toString)

  final case class HasBookings(comboId: ComboId)
      extends ComboError(s"Combo ${comboId.value} has existing bookings and cannot be modified")

  final case class NotFound(comboId: ComboId)
      extends ComboError(s"Combo ${comboId.value} not found")

  final case class Forbidden(comboId: ComboId)
      extends ComboError(s"Provider does not own combo ${comboId.value}")

  final case class ContainsNestedCombo(itemId: ItemId)
      extends ComboError(s"Item ${itemId.value} is a combo and cannot be nested inside a combo")

  final case class ItemBelongsToDifferentProvider(itemId: ItemId)
      extends ComboError(s"Item ${itemId.value} belongs to a different provider")

  final case class ItemNotFound(itemId: ItemId)
      extends ComboError(s"Item ${itemId.value} not found in composition")
