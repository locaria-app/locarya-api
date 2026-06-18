package com.locarya.domain.ports

import com.locarya.domain.models.*

trait AttendantRepository[F[_]] extends Repository[F, Attendant, AttendantId]:
  def findByProvider(providerId: ProviderId): F[List[Attendant]]
  def findActiveByProvider(providerId: ProviderId): F[List[Attendant]]
  def findByBooking(bookingId: BookingId): F[List[Attendant]]
  def assignToBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit]
  def removeFromBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit]
