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

  private case class GroupedRow(
    itemType:    String,
    itemId:      Option[UUID],
    comboId:     Option[UUID],
    attendantId: UUID
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

  override def assignToBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit] =
    val bId  = UUID.fromString(bookingId.value)
    val aId  = UUID.fromString(attendantId.value)
    val frag = lineRef match
      case BookingLineRef.IndividualLine(itemId) =>
        val iId = UUID.fromString(itemId.value)
        sql"""INSERT INTO booking_attendants (id, booking_id, attendant_id, booking_item_id)
              SELECT gen_random_uuid(), $bId, $aId, bi.id
              FROM booking_items bi
              WHERE bi.booking_id = $bId
                AND bi.item_type  = 'INDIVIDUAL'
                AND bi.item_id    = $iId
              LIMIT 1"""
      case BookingLineRef.ComboLine(comboId) =>
        val cId = UUID.fromString(comboId.value)
        sql"""INSERT INTO booking_attendants (id, booking_id, attendant_id, booking_item_id)
              SELECT gen_random_uuid(), $bId, $aId, bi.id
              FROM booking_items bi
              WHERE bi.booking_id = $bId
                AND bi.item_type  = 'COMBO'
                AND bi.combo_id   = $cId
              LIMIT 1"""
    frag.update.run.transact(xa).void

  override def removeFromBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit] =
    val bId  = UUID.fromString(bookingId.value)
    val aId  = UUID.fromString(attendantId.value)
    val frag = lineRef match
      case BookingLineRef.IndividualLine(itemId) =>
        val iId = UUID.fromString(itemId.value)
        sql"""DELETE FROM booking_attendants
              WHERE attendant_id   = $aId
                AND booking_item_id = (
                  SELECT id FROM booking_items
                  WHERE booking_id = $bId
                    AND item_type  = 'INDIVIDUAL'
                    AND item_id    = $iId
                  LIMIT 1
                )"""
      case BookingLineRef.ComboLine(comboId) =>
        val cId = UUID.fromString(comboId.value)
        sql"""DELETE FROM booking_attendants
              WHERE attendant_id   = $aId
                AND booking_item_id = (
                  SELECT id FROM booking_items
                  WHERE booking_id = $bId
                    AND item_type  = 'COMBO'
                    AND combo_id   = $cId
                  LIMIT 1
                )"""
    frag.update.run.transact(xa).void

  override def findByBookingGrouped(bookingId: BookingId): F[Map[BookingLineRef, Set[AttendantId]]] =
    val bId = UUID.fromString(bookingId.value)
    sql"""SELECT bi.item_type, bi.item_id, bi.combo_id, ba.attendant_id
          FROM booking_attendants ba
          JOIN booking_items bi ON bi.id = ba.booking_item_id
          WHERE bi.booking_id = $bId"""
      .query[GroupedRow]
      .to[List]
      .transact(xa)
      .flatMap { rows =>
        rows.traverse { row =>
          val lineRefF: F[BookingLineRef] = row.itemType match
            case "INDIVIDUAL" =>
              row.itemId match
                case Some(uuid) =>
                  ItemId.fromString(uuid.toString)
                    .fold(err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")), id => BookingLineRef.IndividualLine(id).pure[F])
                case None =>
                  Async[F].raiseError(new RuntimeException("INDIVIDUAL booking_item has null item_id"))
            case "COMBO" =>
              row.comboId match
                case Some(uuid) =>
                  ComboId.fromString(uuid.toString)
                    .fold(err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")), id => BookingLineRef.ComboLine(id).pure[F])
                case None =>
                  Async[F].raiseError(new RuntimeException("COMBO booking_item has null combo_id"))
            case other =>
              Async[F].raiseError(new RuntimeException(s"Unknown item_type: $other"))
          val attendantIdF: F[AttendantId] =
            AttendantId.fromString(row.attendantId.toString)
              .fold(err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")), _.pure[F])
          (lineRefF, attendantIdF).tupled
        }.map { pairs =>
          pairs.groupMap(_._1)(_._2).map { case (k, vs) => k -> vs.toSet }
        }
      }

object AttendantRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): AttendantRepository[F] =
    new AttendantRepositoryLive[F](xa)
