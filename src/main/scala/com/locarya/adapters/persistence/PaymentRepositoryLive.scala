package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.PaymentRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

final class PaymentRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends PaymentRepository[F]:

  private case class PaymentRow(
    id:            UUID,
    bookingId:     UUID,
    amount:        BigDecimal,
    paymentMethod: String,
    paidAt:        Option[LocalDateTime],
    note:          Option[String]
  ) derives Read

  private val selectBase = fr"""
    SELECT id, booking_id, amount, payment_method, paid_at, note
    FROM payments
  """

  private def rowToPayment(row: PaymentRow): F[Payment] =
    (for
      id        <- PaymentId.fromString(row.id.toString)
      bookingId <- BookingId.fromString(row.bookingId.toString)
      method    <- PaymentMethod.decode(row.paymentMethod)
      paidAt    <- row.paidAt
                     .map(_.toInstant(ZoneOffset.UTC))
                     .toRight(InvalidPayment("paid_at is null in database"))
      payment   <- Payment.create(id, bookingId, row.amount, method, row.note, paidAt)
    yield payment).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  override def create(payment: Payment): F[Payment] =
    val paidAtLdt = payment.paidAt.atZone(ZoneOffset.UTC).toLocalDateTime
    sql"""INSERT INTO payments
            (id, booking_id, amount, payment_method, status, paid_at, note, created_at, updated_at)
          VALUES
            (${UUID.fromString(payment.id.value)},
             ${UUID.fromString(payment.bookingId.value)},
             ${payment.amount.amount},
             ${PaymentMethod.encode(payment.method)},
             ${"CONFIRMED"},
             $paidAtLdt,
             ${payment.note},
             NOW(), NOW())"""
      .update.run.transact(xa) >> payment.pure[F]

  override def findById(id: PaymentId): F[Option[Payment]] =
    (selectBase ++ fr"WHERE id = ${UUID.fromString(id.value)}")
      .query[PaymentRow].option.transact(xa)
      .flatMap(_.traverse(rowToPayment))

  override def update(payment: Payment): F[Payment] =
    val paidAtLdt = payment.paidAt.atZone(ZoneOffset.UTC).toLocalDateTime
    sql"""UPDATE payments SET
            status     = ${"CONFIRMED"},
            paid_at    = $paidAtLdt,
            note       = ${payment.note},
            updated_at = NOW()
          WHERE id = ${UUID.fromString(payment.id.value)}"""
      .update.run.transact(xa) >> payment.pure[F]

  override def findByBooking(bookingId: BookingId): F[List[Payment]] =
    (selectBase ++ fr"WHERE booking_id = ${UUID.fromString(bookingId.value)}")
      .query[PaymentRow].to[List].transact(xa)
      .flatMap(_.traverse(rowToPayment))

object PaymentRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): PaymentRepository[F] =
    new PaymentRepositoryLive[F](xa)
