package com.locarya.domain.ports

import com.locarya.domain.models.*
import java.time.LocalDate

trait AvailabilityService[F[_]]:
  /** Per-item availability for `date`. Returns one [[ItemAvailability]] per requested id,
    * in request order. Each id is resolved to an Item or a Combo; an id matching neither
    * yields `AvailabilityKind.Unknown` with `available = false`.
    */
  def checkAvailability(
    items:            List[(ItemId, Int)],
    date:             LocalDate,
    excludeBookingId: Option[BookingId]
  ): F[List[ItemAvailability]]
