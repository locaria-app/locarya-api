package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.PaymentRepository

final class InMemoryPaymentRepository[F[_]: Async] private (
  state: Ref[F, Map[PaymentId, Payment]]
) extends PaymentRepository[F]:

  def create(payment: Payment): F[Payment] =
    state.modify { store =>
      if store.contains(payment.id) then
        (store, new RuntimeException(s"Payment ${payment.id.value} already exists").raiseError[F, Payment])
      else
        (store + (payment.id -> payment), payment.pure[F])
    }.flatten

  def findById(id: PaymentId): F[Option[Payment]] =
    state.get.map(_.get(id))

  def update(payment: Payment): F[Payment] =
    state.modify(store => (store + (payment.id -> payment)) -> payment)

  def findByBooking(bookingId: BookingId): F[List[Payment]] =
    state.get.map(_.values.filter(_.bookingId == bookingId).toList)

object InMemoryPaymentRepository:
  def make[F[_]: Async]: F[InMemoryPaymentRepository[F]] =
    Ref.of[F, Map[PaymentId, Payment]](Map.empty).map(new InMemoryPaymentRepository(_))
