package com.locarya.domain.models

enum BookingChargeStatus:
  case Pending, Paid, Cancelled

final case class BookingCharge private (
  id:         BookingChargeId,
  bookingId:  BookingId,
  chargeId:   String,
  paymentUrl: String,
  status:     BookingChargeStatus
)

object BookingCharge:
  def create(
    id:         BookingChargeId,
    bookingId:  BookingId,
    chargeId:   String,
    paymentUrl: String
  ): Either[ValidationError, BookingCharge] =
    if chargeId.isEmpty then Left(InvalidBookingCharge("chargeId must not be empty"))
    else if paymentUrl.isEmpty then Left(InvalidBookingCharge("paymentUrl must not be empty"))
    else Right(BookingCharge(id, bookingId, chargeId, paymentUrl, BookingChargeStatus.Pending))

  def fromDb(
    id:         BookingChargeId,
    bookingId:  BookingId,
    chargeId:   String,
    paymentUrl: String,
    status:     BookingChargeStatus
  ): BookingCharge = BookingCharge(id, bookingId, chargeId, paymentUrl, status)
