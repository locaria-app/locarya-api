package com.locarya.domain.models

sealed abstract class AttendantError(message: String) extends RuntimeException(message)

object AttendantError:
  final case class InvalidInput(error: ValidationError)
      extends AttendantError(error.message)

  final case class AttendantNotFound(id: AttendantId)
      extends AttendantError(s"Attendant '${id.value}' not found")

  final case class AttendantInactive(id: AttendantId)
      extends AttendantError(s"Attendant '${id.value}' is inactive")

  final case class Forbidden(id: AttendantId)
      extends AttendantError(s"Access to attendant '${id.value}' denied")

  final case class BookingNotFound(id: BookingId)
      extends AttendantError(s"Booking '${id.value}' not found")

  final case class BookingLineNotFound(bookingId: BookingId, lineRef: BookingLineRef)
      extends AttendantError(s"Booking line not found in booking '${bookingId.value}'")
