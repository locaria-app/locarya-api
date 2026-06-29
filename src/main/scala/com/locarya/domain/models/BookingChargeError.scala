package com.locarya.domain.models

sealed abstract class BookingChargeError(message: String) extends RuntimeException(message)

object BookingChargeError:
  final case class NotFound(message: String)
      extends BookingChargeError(message)

  final case class OnlinePaymentNotEnabled(providerId: ProviderId)
      extends BookingChargeError(s"Provider '${providerId.value}' does not have online payment enabled")

  final case class InvalidInput(error: ValidationError)
      extends BookingChargeError(error.message)
