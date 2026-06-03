package com.locarya.services

import com.locarya.core.domain.{Booking, BookingId, ProviderId, BookingStatus}
import java.time.LocalDate

trait BookingRepo[F[_]] extends Repository[F, Booking, BookingId] {
  def findByProvider(providerId: ProviderId): F[List[Booking]]
  def findByStatus(status: BookingStatus): F[List[Booking]]
  def findByDateRange(startDate: LocalDate, endDate: LocalDate): F[List[Booking]]
}
