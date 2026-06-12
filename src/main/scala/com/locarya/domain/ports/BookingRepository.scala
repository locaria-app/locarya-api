package com.locarya.domain.ports

import com.locarya.domain.models.*
import java.time.LocalDate

trait BookingRepository[F[_]] extends Repository[F, Booking, BookingId]:
  def findByProvider(providerId: ProviderId): F[List[Booking]]
  def findByStatus(status: BookingStatus): F[List[Booking]]
  def findByDateRange(start: LocalDate, end: LocalDate): F[List[Booking]]
  def existsForItem(itemId: ItemId): F[Boolean]
  def existsForCombo(comboId: ComboId): F[Boolean]
