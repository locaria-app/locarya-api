package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.AttendantRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final class AttendantRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends AttendantRepository[F]:

  private case class AttendantRow(
    id:         UUID,
    providerId: UUID,
    name:       String,
    phone:      Option[String],
    isActive:   Boolean
  ) derives Read

  private def rowToAttendant(row: AttendantRow): F[Attendant] =
    (for
      id         <- AttendantId.fromString(row.id.toString)
      providerId <- ProviderId.fromString(row.providerId.toString)
      attendant  <- Attendant.create(id, providerId, row.name, row.phone.getOrElse(""), row.isActive)
    yield attendant).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  private val selectBase = fr"""
    SELECT id, provider_id, name, phone, is_active
    FROM attendants
  """

  override def create(attendant: Attendant): F[Attendant] =
    sql"""INSERT INTO attendants (id, provider_id, name, phone, is_active)
          VALUES (${UUID.fromString(attendant.id.value)},
                  ${UUID.fromString(attendant.providerId.value)},
                  ${attendant.name},
                  ${attendant.phone},
                  ${attendant.isActive})"""
      .update.run.transact(xa) >> attendant.pure[F]

  override def findById(id: AttendantId): F[Option[Attendant]] =
    val uuid = UUID.fromString(id.value)
    (selectBase ++ fr"WHERE id = $uuid")
      .query[AttendantRow]
      .option
      .transact(xa)
      .flatMap(_.traverse(rowToAttendant))

  override def update(attendant: Attendant): F[Attendant] =
    sql"""UPDATE attendants SET
            name       = ${attendant.name},
            phone      = ${attendant.phone},
            is_active  = ${attendant.isActive},
            updated_at = NOW()
          WHERE id = ${UUID.fromString(attendant.id.value)}"""
      .update.run.transact(xa) >> attendant.pure[F]

  override def findByProvider(providerId: ProviderId): F[List[Attendant]] =
    val uuid = UUID.fromString(providerId.value)
    (selectBase ++ fr"WHERE provider_id = $uuid")
      .query[AttendantRow]
      .to[List]
      .transact(xa)
      .flatMap(_.traverse(rowToAttendant))

  override def findActiveByProvider(providerId: ProviderId): F[List[Attendant]] =
    val uuid = UUID.fromString(providerId.value)
    (selectBase ++ fr"WHERE provider_id = $uuid AND is_active = TRUE")
      .query[AttendantRow]
      .to[List]
      .transact(xa)
      .flatMap(_.traverse(rowToAttendant))

  override def findByBooking(bookingId: BookingId): F[List[Attendant]] =
    val uuid = UUID.fromString(bookingId.value)
    sql"""SELECT a.id, a.provider_id, a.name, a.phone, a.is_active
          FROM attendants a
          JOIN booking_attendants ba ON ba.attendant_id = a.id
          WHERE ba.booking_id = $uuid"""
      .query[AttendantRow]
      .to[List]
      .transact(xa)
      .flatMap(_.traverse(rowToAttendant))

  override def assignToBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit] =
    sql"""INSERT INTO booking_attendants (id, booking_id, attendant_id)
          VALUES (${UUID.randomUUID()},
                  ${UUID.fromString(bookingId.value)},
                  ${UUID.fromString(attendantId.value)})"""
      .update.run.transact(xa).void

  override def removeFromBooking(bookingId: BookingId, attendantId: AttendantId): F[Unit] =
    sql"""DELETE FROM booking_attendants
          WHERE booking_id = ${UUID.fromString(bookingId.value)}
            AND attendant_id = ${UUID.fromString(attendantId.value)}"""
      .update.run.transact(xa).void

object AttendantRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): AttendantRepository[F] =
    new AttendantRepositoryLive[F](xa)
