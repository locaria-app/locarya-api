package com.locarya.domain.ports

import com.locarya.domain.models.*

trait PaymentRepository[F[_]] extends Repository[F, Payment, PaymentId]:
  def findByBooking(bookingId: BookingId): F[List[Payment]]
