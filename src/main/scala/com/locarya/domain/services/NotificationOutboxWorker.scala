package com.locarya.domain.services

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import io.circe.parser.{parse => parseJson}
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final class NotificationOutboxWorker[F[_]: Async](
  notifRepo:       NotificationEventRepository[F],
  bookingRepo:     BookingRepository[F],
  customerRepo:    CustomerRepository[F],
  providerRepo:    ProviderRepository[F],
  paymentRepo:     PaymentRepository[F],
  notificationSvc: NotificationService[F]
):

  def processOnce: F[Unit] =
    notifRepo.findPending(limit = 10).flatMap(_.traverse_(processEvent))

  def stream(interval: FiniteDuration): F[Nothing] =
    (processOnce >> Async[F].sleep(interval)).foreverM

  private def processEvent(event: NotificationEvent): F[Unit] =
    buildPayload(event)
      .flatMap(notificationSvc.notify)
      .flatMap(_ => markProcessed(event))
      .recoverWith { case _ => markRetryOrFailed(event) }

  private def buildPayload(event: NotificationEvent): F[NotificationPayload] =
    parseJson(event.payload).fold(
      e => Async[F].raiseError(new RuntimeException(s"JSON parse error: ${e.message}")),
      json => event.eventType match
        case "PaymentConfirmed" =>
          for
            bookingId  <- fieldOrFail(json, "bookingId")
                            .flatMap(s => parseId(BookingId.fromString(s)))
            paymentId  <- fieldOrFail(json, "paymentId")
                            .flatMap(s => parseId(PaymentId.fromString(s)))
            booking    <- requireById(bookingId, "Booking")(bookingRepo.findById(bookingId))
            customer   <- requireById(booking.customerId, "Customer")(customerRepo.findById(booking.customerId))
            provider   <- requireById(booking.providerId, "Provider")(providerRepo.findById(booking.providerId))
            payment    <- requireById(paymentId, "Payment")(paymentRepo.findById(paymentId))
          yield NotificationPayload.PaymentConfirmed(booking, customer, provider, payment.amount)

        case "BookingCreatedWithPaymentLink" =>
          for
            bookingId  <- fieldOrFail(json, "bookingId")
                            .flatMap(s => parseId(BookingId.fromString(s)))
            paymentUrl <- fieldOrFail(json, "paymentUrl")
            booking    <- requireById(bookingId, "Booking")(bookingRepo.findById(bookingId))
            customer   <- requireById(booking.customerId, "Customer")(customerRepo.findById(booking.customerId))
            provider   <- requireById(booking.providerId, "Provider")(providerRepo.findById(booking.providerId))
          yield NotificationPayload.BookingCreatedWithPaymentLink(booking, customer, provider, paymentUrl)

        case other =>
          Async[F].raiseError(new RuntimeException(s"Unknown notification event type: $other"))
    )

  private def parseId[A](result: Either[ValidationError, A]): F[A] =
    result.fold(e => Async[F].raiseError(new RuntimeException(e.toString)), _.pure[F])

  private def fieldOrFail(json: io.circe.Json, field: String): F[String] =
    json.hcursor.downField(field).as[String].fold(
      e => Async[F].raiseError(new RuntimeException(s"Missing field '$field': ${e.message}")),
      _.pure[F]
    )

  private def requireById[ID, A](id: ID, label: String)(query: F[Option[A]]): F[A] =
    query.flatMap {
      case Some(a) => a.pure[F]
      case None    => Async[F].raiseError(new RuntimeException(s"$label not found for id: $id"))
    }

  private def markProcessed(event: NotificationEvent): F[Unit] =
    notifRepo
      .update(
        NotificationEvent.fromDb(
          event.id, event.eventType, event.payload,
          NotificationEventStatus.Processed, event.retryCount,
          event.createdAt, Some(Instant.now())
        )
      )
      .void

  private def markRetryOrFailed(event: NotificationEvent): F[Unit] =
    val newCount  = event.retryCount + 1
    val newStatus = if newCount >= 3 then NotificationEventStatus.Failed else NotificationEventStatus.Pending
    notifRepo
      .update(
        NotificationEvent.fromDb(
          event.id, event.eventType, event.payload,
          newStatus, newCount, event.createdAt, event.processedAt
        )
      )
      .void

object NotificationOutboxWorker:
  def apply[F[_]: Async](
    notifRepo:       NotificationEventRepository[F],
    bookingRepo:     BookingRepository[F],
    customerRepo:    CustomerRepository[F],
    providerRepo:    ProviderRepository[F],
    paymentRepo:     PaymentRepository[F],
    notificationSvc: NotificationService[F]
  ): NotificationOutboxWorker[F] =
    new NotificationOutboxWorker[F](notifRepo, bookingRepo, customerRepo, providerRepo, paymentRepo, notificationSvc)
