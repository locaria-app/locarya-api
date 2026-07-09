package com.locarya.domain.ports

import com.locarya.domain.models.*

trait AttendantRepository[F[_]] extends Repository[F, Attendant, AttendantId]:
  def findByProvider(providerId: ProviderId): F[List[Attendant]]
  def findActiveByProvider(providerId: ProviderId): F[List[Attendant]]
  def assignToBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit]
  def removeFromBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit]
  def findByBookingGrouped(bookingId: BookingId): F[Map[BookingLineRef, Set[AttendantId]]]
