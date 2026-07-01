package com.locarya.domain.models

import java.time.Instant

enum BookingChargeStatus:
  case Pending, Paid, Cancelled, Expired

final case class BookingCharge private (
  id:         BookingChargeId,
  bookingId:  BookingId,
  chargeId:   String,
  paymentUrl: String,
  status:     BookingChargeStatus,
  createdAt:  Instant
)

object BookingCharge:
  def create(
    id:         BookingChargeId,
    bookingId:  BookingId,
    chargeId:   String,
    paymentUrl: String,
    createdAt:  Instant
  ): Either[ValidationError, BookingCharge] =
    if chargeId.isEmpty then Left(InvalidBookingCharge("chargeId must not be empty"))
    else if paymentUrl.isEmpty then Left(InvalidBookingCharge("paymentUrl must not be empty"))
    else Right(BookingCharge(id, bookingId, chargeId, paymentUrl, BookingChargeStatus.Pending, createdAt))

  def fromDb(
    id:         BookingChargeId,
    bookingId:  BookingId,
    chargeId:   String,
    paymentUrl: String,
    status:     BookingChargeStatus,
    createdAt:  Instant
  ): BookingCharge = BookingCharge(id, bookingId, chargeId, paymentUrl, status, createdAt)
