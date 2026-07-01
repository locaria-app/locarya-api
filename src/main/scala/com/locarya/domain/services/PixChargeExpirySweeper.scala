package com.locarya.domain.services

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.typelevel.log4cats.Logger
import scala.concurrent.duration.FiniteDuration

final class PixChargeExpirySweeper[F[_]: Async: Logger](
  chargeRepo: BookingChargeRepository[F],
  gateway:    PaymentGateway[F]
):

  def processOnce: F[Unit] =
    val cutoff = Instant.now().minus(48, ChronoUnit.HOURS)
    chargeRepo.findPendingOlderThan(cutoff).flatMap(_.traverse_(expireCharge))

  def stream(interval: FiniteDuration): F[Nothing] =
    (processOnce >> Async[F].sleep(interval)).foreverM

  private def expireCharge(charge: BookingCharge): F[Unit] =
    (for
      _ <- gateway.cancelCharge(charge.chargeId)
      expired = BookingCharge.fromDb(
                  charge.id, charge.bookingId, charge.chargeId, charge.paymentUrl,
                  BookingChargeStatus.Expired, charge.createdAt
                )
      _ <- chargeRepo.update(expired)
      _ <- Logger[F].info(expiredLog(charge))
    yield ())
      .handleErrorWith { err =>
        Logger[F].error(err)(s"Failed to expire charge ${charge.chargeId}: ${err.getMessage}")
      }

  private def expiredLog(charge: BookingCharge): String =
    s"""{"event":"PixChargeExpired","chargeId":"${charge.chargeId}","bookingId":"${charge.bookingId.value}"}"""

object PixChargeExpirySweeper:
  def apply[F[_]: Async: Logger](
    chargeRepo: BookingChargeRepository[F],
    gateway:    PaymentGateway[F]
  ): PixChargeExpirySweeper[F] =
    new PixChargeExpirySweeper[F](chargeRepo, gateway)
