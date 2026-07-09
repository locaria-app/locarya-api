package com.locarya.domain.models

sealed trait BookingLineRef
object BookingLineRef:
  final case class IndividualLine(itemId: ItemId)  extends BookingLineRef
  final case class ComboLine(comboId: ComboId)     extends BookingLineRef
