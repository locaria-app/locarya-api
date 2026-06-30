package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.BookingChargeRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class BookingChargeRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends BookingChargeRepository[F]:

  private case class ChargeRow(
    id:            UUID,
    bookingId:     UUID,
    asaasChargeId: String,
    paymentUrl:    String,
    status:        String
  ) derives Read

  private val selectBase = fr"""
    SELECT id, booking_id, asaas_charge_id, payment_url, status
    FROM booking_charges
  """

  private def rowToCharge(row: ChargeRow): F[BookingCharge] =
    (for
      id        <- BookingChargeId.fromString(row.id.toString)
      bookingId <- BookingId.fromString(row.bookingId.toString)
      status    <- row.status match
                     case "pending"   => Right(BookingChargeStatus.Pending)
                     case "paid"      => Right(BookingChargeStatus.Paid)
                     case "cancelled" => Right(BookingChargeStatus.Cancelled)
                     case other       => Left(InvalidBookingCharge(s"Unknown charge status: $other"))
    yield BookingCharge.fromDb(id, bookingId, row.asaasChargeId, row.paymentUrl, status))
      .fold(
        err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
        _.pure[F]
      )

  override def create(charge: BookingCharge): F[BookingCharge] =
    val statusStr = charge.status match
      case BookingChargeStatus.Pending   => "pending"
      case BookingChargeStatus.Paid      => "paid"
      case BookingChargeStatus.Cancelled => "cancelled"
    sql"""INSERT INTO booking_charges
            (id, booking_id, asaas_charge_id, payment_url, status)
          VALUES
            (${UUID.fromString(charge.id.value)},
             ${UUID.fromString(charge.bookingId.value)},
             ${charge.chargeId},
             ${charge.paymentUrl},
             $statusStr)"""
      .update.run.transact(xa) >> charge.pure[F]

  override def findById(id: BookingChargeId): F[Option[BookingCharge]] =
    (selectBase ++ fr"WHERE id = ${UUID.fromString(id.value)}")
      .query[ChargeRow].option.transact(xa)
      .flatMap(_.traverse(rowToCharge))

  override def update(charge: BookingCharge): F[BookingCharge] =
    val statusStr = charge.status match
      case BookingChargeStatus.Pending   => "pending"
      case BookingChargeStatus.Paid      => "paid"
      case BookingChargeStatus.Cancelled => "cancelled"
    sql"""UPDATE booking_charges SET
            status = $statusStr
          WHERE id = ${UUID.fromString(charge.id.value)}"""
      .update.run.transact(xa) >> charge.pure[F]

  override def findPendingByBooking(bookingId: BookingId): F[Option[BookingCharge]] =
    (selectBase ++ fr"WHERE booking_id = ${UUID.fromString(bookingId.value)} AND status = 'pending' LIMIT 1")
      .query[ChargeRow].option.transact(xa)
      .flatMap(_.traverse(rowToCharge))

  override def findByAsaasChargeId(chargeId: String): F[Option[BookingCharge]] =
    (selectBase ++ fr"WHERE asaas_charge_id = $chargeId LIMIT 1")
      .query[ChargeRow].option.transact(xa)
      .flatMap(_.traverse(rowToCharge))

object BookingChargeRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): BookingChargeRepository[F] =
    new BookingChargeRepositoryLive[F](xa)
