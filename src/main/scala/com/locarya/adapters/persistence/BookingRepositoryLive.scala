package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.BookingRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.{fragments => Fragments}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode as circeDecoder
import io.circe.syntax.*
import java.time.{LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID

final class BookingRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends BookingRepository[F]:

  private given Decoder[PartyProfile] = deriveDecoder
  private given Encoder[PartyProfile] = deriveEncoder

  private case class BookingRow(
    id:                     UUID,
    providerId:             UUID,
    customerId:             UUID,
    startDate:              LocalDate,
    endDate:                LocalDate,
    totalAmount:            BigDecimal,
    status:                 String,
    createdBy:              String,
    deliveryStreet:         Option[String],
    deliveryNumber:         Option[String],
    deliveryNeighborhood:   Option[String],
    deliveryCity:           Option[String],
    deliveryState:          Option[String],
    deliveryCep:            Option[String],
    deliveryComplement:     Option[String],
    bookingCode:            String,
    partyProfile:           Option[String],
    confirmedWithoutMonitor: Boolean,
    createdAt:              LocalDateTime
  ) derives Read

  private case class BookingItemRow(
    bookingId: UUID,
    itemType:  String,
    itemId:    Option[UUID],
    comboId:   Option[UUID],
    quantity:  Int,
    unitPrice: Option[BigDecimal]
  ) derives Read

  private val selectBase = fr"""
    SELECT id, provider_id, customer_id, start_date, end_date, total_amount, status, created_by,
           delivery_street, delivery_number, delivery_neighborhood, delivery_city, delivery_state,
           delivery_cep, delivery_complement, booking_code, party_profile::text, confirmed_without_monitor,
           created_at
    FROM bookings
  """

  private def statusStr(s: BookingStatus): String = s match
    case BookingStatus.Pending    => "PENDING"
    case BookingStatus.Confirmed  => "CONFIRMED"
    case BookingStatus.InProgress => "IN_PROGRESS"
    case BookingStatus.Completed  => "COMPLETED"
    case BookingStatus.Cancelled  => "CANCELLED"

  private def parseStatus(s: String): Either[ValidationError, BookingStatus] = s match
    case "PENDING"     => Right(BookingStatus.Pending)
    case "CONFIRMED"   => Right(BookingStatus.Confirmed)
    case "IN_PROGRESS" => Right(BookingStatus.InProgress)
    case "COMPLETED"   => Right(BookingStatus.Completed)
    case "CANCELLED"   => Right(BookingStatus.Cancelled)
    case other         => Left(InvalidBooking(s"Unknown booking status: $other"))

  private def creatorStr(c: BookingCreator): String = c match
    case BookingCreator.Provider => "PROVIDER"
    case BookingCreator.Customer => "CUSTOMER"

  private def parseCreator(s: String): Either[ValidationError, BookingCreator] = s match
    case "PROVIDER" => Right(BookingCreator.Provider)
    case "CUSTOMER" => Right(BookingCreator.Customer)
    case other      => Left(InvalidBooking(s"Unknown created_by value: $other"))

  private def parseDeliveryAddress(row: BookingRow): Either[ValidationError, Option[Address]] =
    (row.deliveryStreet, row.deliveryNumber, row.deliveryNeighborhood,
     row.deliveryCity, row.deliveryState, row.deliveryCep) match
      case (Some(s), Some(n), Some(nb), Some(c), Some(st), Some(cp)) =>
        Address.create(s, n, nb, c, st, cp, row.deliveryComplement).map(Some(_))
      case (None, None, None, None, None, None) =>
        Right(None)
      case _ =>
        Left(InvalidAddress("Partial delivery address data in database"))

  private def rowToBookingItem(row: BookingItemRow): Either[ValidationError, BookingItem] =
    row.itemType match
      case "INDIVIDUAL" =>
        for
          uid      <- row.itemId.toRight(InvalidBooking("INDIVIDUAL booking item missing item_id"))
          itemId   <- ItemId.fromString(uid.toString)
          priceOpt <- row.unitPrice.traverse(Money.fromAmount)
        yield BookedIndividualItem(itemId, row.quantity, priceOpt)
      case "COMBO" =>
        for
          uid      <- row.comboId.toRight(InvalidBooking("COMBO booking item missing combo_id"))
          comboId  <- ComboId.fromString(uid.toString)
          priceOpt <- row.unitPrice.traverse(Money.fromAmount)
        yield BookedCombo(comboId, row.quantity, priceOpt)
      case other =>
        Left(InvalidBooking(s"Unknown item_type: $other"))

  private def rowToBooking(
    row:   BookingRow,
    items: List[BookingItemRow]
  ): F[Booking] =
    val partyProfileOpt = row.partyProfile.flatMap(json => circeDecoder[PartyProfile](json).toOption)
    (for
      id           <- BookingId.fromString(row.id.toString)
      pid          <- ProviderId.fromString(row.providerId.toString)
      cid          <- CustomerId.fromString(row.customerId.toString)
      bookingItems <- items.traverse(rowToBookingItem)
      totalAmt     <- Money.fromAmount(row.totalAmount)
      status       <- parseStatus(row.status)
      deliverAddr  <- parseDeliveryAddress(row)
      creator      <- parseCreator(row.createdBy)
      code         <- BookingCode.fromString(row.bookingCode)
      b            <- Booking.create(id, pid, cid, bookingItems, row.startDate, row.endDate,
                        totalAmt, row.createdAt.toInstant(ZoneOffset.UTC), status, deliverAddr, creator, code, partyProfileOpt)
      booking       = if row.confirmedWithoutMonitor then b.markConfirmedWithoutMonitor else b
    yield booking).fold(
      err => Async[F].raiseError(new RuntimeException(s"DB→domain mapping failed: $err")),
      _.pure[F]
    )

  private def insertItemRows(bookingUuid: UUID, items: List[BookingItem]): ConnectionIO[Unit] =
    items.traverse_ { item =>
      val (itemType, itemUuid, comboUuid, qty, unitPrice) = item match
        case BookedIndividualItem(itemId, q, price) =>
          ("INDIVIDUAL", Some(UUID.fromString(itemId.value)), Option.empty[UUID], q, price.map(_.amount))
        case BookedCombo(comboId, q, price) =>
          ("COMBO", Option.empty[UUID], Some(UUID.fromString(comboId.value)), q, price.map(_.amount))
      sql"""INSERT INTO booking_items (id, booking_id, item_type, item_id, combo_id, quantity, unit_price)
            VALUES (${UUID.randomUUID()}, $bookingUuid, $itemType, $itemUuid, $comboUuid, $qty, $unitPrice)"""
        .update.run.void
    }

  private def findBookingsWhere(whereClause: Fragment): F[List[Booking]] =
    (selectBase ++ whereClause).query[BookingRow].to[List].transact(xa).flatMap { rows =>
      if rows.isEmpty then List.empty[Booking].pure[F]
      else
        val uuids = rows.map(_.id).toArray
        sql"""SELECT booking_id, item_type, item_id, combo_id, quantity, unit_price
              FROM booking_items WHERE booking_id = ANY($uuids)"""
          .query[BookingItemRow].to[List].transact(xa).flatMap { allItems =>
            val itemsByBooking = allItems.groupBy(_.bookingId)
            rows.traverse(row => rowToBooking(row, itemsByBooking.getOrElse(row.id, Nil)))
          }
    }

  override def create(booking: Booking): F[Booking] =
    val bookingUuid     = UUID.fromString(booking.id.value)
    val partyProfileFr  = booking.partyProfile
      .map(p => fr"${p.asJson.noSpaces}::jsonb")
      .getOrElse(fr"NULL")
    (for
      _ <- (fr"""INSERT INTO bookings
                   (id, provider_id, customer_id, start_date, end_date, total_amount, status, created_by,
                    delivery_street, delivery_number, delivery_neighborhood, delivery_city,
                    delivery_state, delivery_cep, delivery_complement, booking_code, party_profile,
                    confirmed_without_monitor)
                 VALUES
                   ($bookingUuid,
                    ${UUID.fromString(booking.providerId.value)},
                    ${UUID.fromString(booking.customerId.value)},
                    ${booking.startDate}, ${booking.endDate},
                    ${booking.totalAmount.amount},
                    ${statusStr(booking.status)},
                    ${creatorStr(booking.createdBy)},
                    ${booking.deliveryAddress.map(_.street)},
                    ${booking.deliveryAddress.map(_.number)},
                    ${booking.deliveryAddress.map(_.neighborhood)},
                    ${booking.deliveryAddress.map(_.city)},
                    ${booking.deliveryAddress.map(_.state)},
                    ${booking.deliveryAddress.map(_.cep)},
                    ${booking.deliveryAddress.flatMap(_.complement)},
                    ${booking.bookingCode.value},""" ++ partyProfileFr ++ fr", ${booking.confirmedWithoutMonitor})")
              .update.run
      _ <- insertItemRows(bookingUuid, booking.items)
    yield ()).transact(xa) >> booking.pure[F]

  override def findById(id: BookingId): F[Option[Booking]] =
    val bookingUuid = UUID.fromString(id.value)
    (for
      rowOpt <- (selectBase ++ fr"WHERE id = $bookingUuid").query[BookingRow].option
      items  <- sql"""SELECT booking_id, item_type, item_id, combo_id, quantity, unit_price
                      FROM booking_items WHERE booking_id = $bookingUuid"""
                  .query[BookingItemRow].to[List]
    yield (rowOpt, items)).transact(xa).flatMap {
      case (Some(row), items) => rowToBooking(row, items).map(Some(_))
      case (None, _)          => (None: Option[Booking]).pure[F]
    }

  override def update(booking: Booking): F[Booking] =
    val bookingUuid    = UUID.fromString(booking.id.value)
    val partyProfileFr = booking.partyProfile
      .map(p => fr"${p.asJson.noSpaces}::jsonb")
      .getOrElse(fr"NULL")
    (for
      _ <- (fr"""UPDATE bookings SET
                   status                    = ${statusStr(booking.status)},
                   start_date                = ${booking.startDate},
                   end_date                  = ${booking.endDate},
                   total_amount              = ${booking.totalAmount.amount},
                   created_by                = ${creatorStr(booking.createdBy)},
                   delivery_street           = ${booking.deliveryAddress.map(_.street)},
                   delivery_number           = ${booking.deliveryAddress.map(_.number)},
                   delivery_neighborhood     = ${booking.deliveryAddress.map(_.neighborhood)},
                   delivery_city             = ${booking.deliveryAddress.map(_.city)},
                   delivery_state            = ${booking.deliveryAddress.map(_.state)},
                   delivery_cep              = ${booking.deliveryAddress.map(_.cep)},
                   delivery_complement       = ${booking.deliveryAddress.flatMap(_.complement)},
                   confirmed_without_monitor = ${booking.confirmedWithoutMonitor},
                   party_profile             = """ ++ partyProfileFr ++ fr""",
                   updated_at                = NOW()
                 WHERE id = $bookingUuid""").update.run
      _ <- sql"DELETE FROM booking_items WHERE booking_id = $bookingUuid".update.run
      _ <- insertItemRows(bookingUuid, booking.items)
    yield ()).transact(xa) >> booking.pure[F]

  override def findByProvider(
    providerId: ProviderId,
    status:     Option[BookingStatus],
    dateFrom:   Option[LocalDate],
    dateTo:     Option[LocalDate]
  ): F[List[Booking]] =
    val providerFr = Some(fr"provider_id = ${UUID.fromString(providerId.value)}")
    val statusFr   = status.map(s => fr"status = ${statusStr(s)}")
    val dateFromFr = dateFrom.map(d => fr"start_date >= $d")
    val dateToFr   = dateTo.map(d => fr"start_date <= $d")
    findBookingsWhere(Fragments.whereAndOpt(providerFr, statusFr, dateFromFr, dateToFr))

  override def findByStatus(status: BookingStatus): F[List[Booking]] =
    findBookingsWhere(fr"WHERE status = ${statusStr(status)}")

  override def findByDateRange(start: LocalDate, end: LocalDate): F[List[Booking]] =
    findBookingsWhere(fr"WHERE start_date <= $end AND end_date >= $start")

  override def existsForItem(itemId: ItemId): F[Boolean] =
    sql"""SELECT EXISTS(
            SELECT 1 FROM booking_items
            WHERE item_type = 'INDIVIDUAL' AND item_id = ${UUID.fromString(itemId.value)}
          )"""
      .query[Boolean].unique.transact(xa)

  override def existsForCombo(comboId: ComboId): F[Boolean] =
    sql"""SELECT EXISTS(
            SELECT 1 FROM booking_items
            WHERE item_type = 'COMBO' AND combo_id = ${UUID.fromString(comboId.value)}
          )"""
      .query[Boolean].unique.transact(xa)

  override def findByCode(code: BookingCode): F[Option[Booking]] =
    findBookingsWhere(fr"WHERE booking_code = ${code.value}").map(_.headOption)

object BookingRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): BookingRepository[F] =
    new BookingRepositoryLive[F](xa)
