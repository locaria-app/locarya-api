package com.locarya.helpers

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.{AsaasCharge, BookingId}
import com.locarya.domain.ports.PaymentGateway

final class PaymentGatewayStub[F[_]: Sync] private (
  createCounter: Ref[F, Int],
  cancelCounter: Ref[F, Int]
) extends PaymentGateway[F]:

  def createCharge(bookingId: BookingId, walletId: String, amount: BigDecimal, customerEmail: String): F[AsaasCharge] =
    createCounter.update(_ + 1) >>
      AsaasCharge
        .create(s"charge_${bookingId.value}", s"https://asaas.com/pay/${bookingId.value}")
        .fold(e => Sync[F].raiseError(new RuntimeException(e.message)), _.pure[F])

  def cancelCharge(chargeId: String): F[Unit] =
    cancelCounter.update(_ + 1)

  def createChargeCallCount: F[Int] = createCounter.get
  def cancelChargeCallCount: F[Int] = cancelCounter.get

object PaymentGatewayStub:
  def make[F[_]: Sync]: F[PaymentGatewayStub[F]] =
    for
      cc <- Ref.of[F, Int](0)
      xc <- Ref.of[F, Int](0)
    yield new PaymentGatewayStub[F](cc, xc)
