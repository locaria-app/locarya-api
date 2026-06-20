package com.locarya.domain.models

sealed trait ValidationError:
  def message: String

case class InvalidAmount(message: String) extends ValidationError
case class InvalidEmail(message: String) extends ValidationError
case class InvalidCPF(message: String) extends ValidationError
case class InvalidCNPJ(message: String) extends ValidationError
case class InvalidTaxId(message: String) extends ValidationError
case class InvalidEntityId(message: String) extends ValidationError
case class InvalidStatusTransition(message: String) extends ValidationError
case class InvalidProvider(message: String) extends ValidationError
case class InvalidCustomer(message: String) extends ValidationError
case class InvalidItem(message: String) extends ValidationError
case class InvalidCombo(message: String) extends ValidationError
case class InvalidBooking(message: String) extends ValidationError
case class InvalidAddress(message: String)       extends ValidationError
case class InvalidURL(message: String)            extends ValidationError
case class InvalidStorefrontSlug(message: String) extends ValidationError
case class InvalidPassword(message: String)       extends ValidationError
case class InvalidItemImage(message: String)      extends ValidationError
case class InvalidAvailabilityQuery(message: String) extends ValidationError
case class InvalidAttendant(message: String)          extends ValidationError
case class InvalidPayment(message: String)            extends ValidationError
case class InvalidPlan(message: String)               extends ValidationError
case class InvalidSubscription(message: String)       extends ValidationError
