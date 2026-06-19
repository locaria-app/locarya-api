package com.locarya.domain.models

sealed abstract class PaymentError(message: String) extends RuntimeException(message)

object PaymentError:
  final case class InvalidInput(error: ValidationError)
      extends PaymentError(error.message)

  final case class BookingNotFound(id: BookingId)
      extends PaymentError(s"Booking '${id.value}' not found")

  final case class PaymentNotFound(id: PaymentId)
      extends PaymentError(s"Payment '${id.value}' not found")

  final case class Forbidden(id: BookingId)
      extends PaymentError(s"Access denied for booking '${id.value}'")
