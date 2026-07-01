package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.BookingChargeRepository
import java.time.Instant

final class InMemoryBookingChargeRepository[F[_]: Async] private (
  state: Ref[F, Map[BookingChargeId, BookingCharge]]
) extends BookingChargeRepository[F]:

  def create(charge: BookingCharge): F[BookingCharge] =
    state.modify { store =>
      if store.contains(charge.id) then
        (store, new RuntimeException(s"BookingCharge ${charge.id.value} already exists").raiseError[F, BookingCharge])
      else
        (store + (charge.id -> charge), charge.pure[F])
    }.flatten

  def findById(id: BookingChargeId): F[Option[BookingCharge]] =
    state.get.map(_.get(id))

  def update(charge: BookingCharge): F[BookingCharge] =
    state.modify(store => (store + (charge.id -> charge)) -> charge)

  def findPendingByBooking(bookingId: BookingId): F[Option[BookingCharge]] =
    state.get.map(
      _.values.find(c => c.bookingId == bookingId && c.status == BookingChargeStatus.Pending)
    )

  def findByAsaasChargeId(chargeId: String): F[Option[BookingCharge]] =
    state.get.map(_.values.find(_.chargeId == chargeId))

  def findPendingOlderThan(cutoff: Instant): F[List[BookingCharge]] =
    state.get.map(
      _.values
        .filter(c => c.status == BookingChargeStatus.Pending && c.createdAt.isBefore(cutoff))
        .toList
    )

object InMemoryBookingChargeRepository:
  def make[F[_]: Async]: F[InMemoryBookingChargeRepository[F]] =
    Ref.of[F, Map[BookingChargeId, BookingCharge]](Map.empty).map(new InMemoryBookingChargeRepository(_))
