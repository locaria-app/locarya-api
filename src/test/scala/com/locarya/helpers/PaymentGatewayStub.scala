package com.locarya.helpers

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.{AsaasCharge, BookingId}
import com.locarya.domain.ports.PaymentGateway

final class PaymentGatewayStub[F[_]: Sync] private (
  createCounter: Ref[F, Int],
  cancelLog:     Ref[F, List[String]],
  failingIds:    Ref[F, Set[String]]
) extends PaymentGateway[F]:

  def createCharge(bookingId: BookingId, walletId: String, amount: BigDecimal, customerEmail: String): F[AsaasCharge] =
    createCounter.update(_ + 1) >>
      AsaasCharge
        .create(s"charge_${bookingId.value}", s"https://asaas.com/pay/${bookingId.value}")
        .fold(e => Sync[F].raiseError(new RuntimeException(e.message)), _.pure[F])

  def cancelCharge(chargeId: String): F[Unit] =
    failingIds.get.flatMap { failing =>
      if failing.contains(chargeId)
      then Sync[F].raiseError(new RuntimeException(s"Simulated cancelCharge failure for $chargeId"))
      else cancelLog.update(_ :+ chargeId)
    }

  def createChargeCallCount: F[Int]   = createCounter.get
  def cancelChargeCallCount: F[Int]   = cancelLog.get.map(_.size)
  def cancelledChargeIds: F[List[String]] = cancelLog.get
  def failFor(chargeId: String): F[Unit]  = failingIds.update(_ + chargeId)

object PaymentGatewayStub:
  def make[F[_]: Sync]: F[PaymentGatewayStub[F]] =
    for
      cc   <- Ref.of[F, Int](0)
      cLog <- Ref.of[F, List[String]](List.empty)
      fail <- Ref.of[F, Set[String]](Set.empty)
    yield new PaymentGatewayStub[F](cc, cLog, fail)
