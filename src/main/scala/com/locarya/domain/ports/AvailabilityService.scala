package com.locarya.domain.ports

import com.locarya.domain.models.*
import java.time.LocalDate

trait AvailabilityService[F[_]]:
  def checkAvailability(
    items:            List[(ItemId, Int)],
    combos:           List[(ComboId, Int)],
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[AvailabilityResult]
