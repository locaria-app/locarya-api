package com.locarya.domain.services

import cats.MonadThrow
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.Instant

final class AsaasWebhookServiceImpl[F[_]: MonadThrow](
  chargeRepo:  BookingChargeRepository[F],
  paymentRepo: PaymentRepository[F],
  notifRepo:   NotificationEventRepository[F]
) extends AsaasWebhookService[F]:

  def handlePaymentConfirmed(asaasChargeId: String, amount: BigDecimal): F[Unit] =
    chargeRepo.findByAsaasChargeId(asaasChargeId).flatMap {
      case None                                                       => ().pure[F]
      case Some(charge) if charge.status == BookingChargeStatus.Paid => ().pure[F]
      case Some(charge) =>
        val now = Instant.now()
        for
          payment <- Payment
                       .create(PaymentId.generate, charge.bookingId, amount, PaymentMethod.PixAsaas, None, now)
                       .fold(e => MonadThrow[F].raiseError(new RuntimeException(e.toString)), _.pure[F])
          _        <- paymentRepo.create(payment)
          paidCharge = BookingCharge.fromDb(
                         charge.id, charge.bookingId, charge.chargeId, charge.paymentUrl, BookingChargeStatus.Paid
                       )
          _        <- chargeRepo.update(paidCharge)
          payload   = s"""{"asaasChargeId":"$asaasChargeId","bookingId":"${charge.bookingId.value}","paymentId":"${payment.id.value}"}"""
          event     = NotificationEvent.create(NotificationEventId.generate, "PaymentConfirmed", payload, now)
          _        <- notifRepo.create(event)
        yield ()
    }
