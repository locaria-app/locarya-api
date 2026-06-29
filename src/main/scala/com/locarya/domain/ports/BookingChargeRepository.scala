package com.locarya.domain.ports

import com.locarya.domain.models.*

trait BookingChargeRepository[F[_]] extends Repository[F, BookingCharge, BookingChargeId]:
  def findPendingByBooking(bookingId: BookingId): F[Option[BookingCharge]]
