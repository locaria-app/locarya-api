package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.LocalDate
import org.typelevel.log4cats.Logger

class BookingServiceImpl[F[_]: Sync: Logger](
  providerRepo: ProviderRepository[F],
  customerRepo: CustomerRepository[F],
  bookingRepo:  BookingRepository[F],
  itemRepo:     ItemRepository[F],
  comboRepo:    ComboRepository[F],
  availability: AvailabilityService[F]
) extends BookingService[F]:

  /** A requested line resolved to its booked representation and the price snapshot
    * captured at creation time. */
  private final case class ResolvedLine(item: BookingItem, lineTotal: BigDecimal)

  def createBooking(request: CreateBookingRequest): F[BookingCreated] =
    for
      provider  <- requireProvider(request.slug)
      _         <- requireAvailable(request)
      customer  <- upsertCustomer(request.customer)
      lines     <- request.items.traverse(resolveLine)
      total     <- totalAmount(lines)
      booking   <- liftValidation(
                     Booking.create(
                       id              = BookingId.generate,
                       providerId      = provider.id,
                       customerId      = customer.id,
                       items           = lines.map(_.item),
                       startDate       = request.date,
                       endDate         = request.date,
                       totalAmount     = total,
                       status          = BookingStatus.Pending,
                       deliveryAddress = Some(request.deliveryAddress),
                       createdBy       = BookingCreator.Customer
                     )
                   )
      stored    <- bookingRepo.create(booking)
      _         <- Logger[F].info(bookingCreatedLog(stored, customer))
    yield BookingCreated(stored.id, stored.status, stored.totalAmount)

  private def requireProvider(slug: StorefrontSlug): F[Provider] =
    providerRepo.findBySlug(slug).flatMap {
      case Some(p) => p.pure[F]
      case None    => BookingError.ProviderNotFound(slug).raiseError[F, Provider]
    }

  /** Validate availability for every requested item on the date BEFORE persisting. */
  private def requireAvailable(request: CreateBookingRequest): F[Unit] =
    val lines = request.items.map(l => (l.itemId, l.quantity))
    availability.checkAvailability(lines, request.date, None).flatMap { results =>
      val unavailable = results.filterNot(_.available)
      if unavailable.isEmpty then ().pure[F]
      else
        Logger[F].info(availabilityFailedLog(request.date, results, unavailable)) *>
          BookingError.ItemsUnavailable(unavailable).raiseError[F, Unit]
    }

  /** Find the Customer by email, creating one when none exists yet (upsert by email). */
  private def upsertCustomer(input: CustomerInput): F[Customer] =
    customerRepo.findByEmail(input.email).flatMap {
      case Some(existing) => existing.pure[F]
      case None =>
        liftValidation(
          Customer.create(
            id    = CustomerId.generate,
            email = input.email,
            name  = input.name,
            phone = input.phone
          )
        ).flatMap(customerRepo.create)
    }

  /** Resolve a requested id to an Item or Combo and capture its current price. Availability
    * was already validated, so a miss here is an unexpected internal inconsistency. */
  private def resolveLine(line: BookingLineInput): F[ResolvedLine] =
    itemRepo.findById(line.itemId).flatMap {
      case Some(item) =>
        ResolvedLine(
          BookedIndividualItem(item.id, line.quantity, Some(item.dailyRate)),
          item.dailyRate.amount * line.quantity
        ).pure[F]
      case None =>
        ComboId.fromString(line.itemId.value) match
          case Right(comboId) =>
            comboRepo.findById(comboId).flatMap {
              case Some(combo) =>
                ResolvedLine(
                  BookedCombo(combo.id, line.quantity, Some(combo.dailyRate)),
                  combo.dailyRate.amount * line.quantity
                ).pure[F]
              case None =>
                BookingError.InvalidInput(InvalidBooking(s"Unknown item ${line.itemId.value}")).raiseError[F, ResolvedLine]
            }
          case Left(err) =>
            BookingError.InvalidInput(err).raiseError[F, ResolvedLine]
    }

  private def totalAmount(lines: List[ResolvedLine]): F[Money] =
    liftValidation(Money.fromAmount(lines.map(_.lineTotal).sum))

  private def liftValidation[A](e: Either[ValidationError, A]): F[A] =
    e.fold(err => BookingError.InvalidInput(err).raiseError[F, A], _.pure[F])

  private def bookingCreatedLog(booking: Booking, customer: Customer): String =
    val itemIds = booking.items.map {
      case BookedIndividualItem(id, _, _) => s""""${id.value}""""
      case BookedCombo(id, _, _)          => s""""${id.value}""""
    }.mkString(",")
    val createdBy = booking.createdBy.toString.toLowerCase
    s"""{"event":"BookingCreated","bookingId":"${booking.id.value}","providerId":"${booking.providerId.value}","customerId":"${customer.id.value}","itemIds":[$itemIds],"date":"${booking.startDate}","createdBy":"$createdBy"}"""

  private def availabilityFailedLog(
    date:        LocalDate,
    results:     List[ItemAvailability],
    unavailable: List[ItemAvailability]
  ): String =
    val itemIds        = results.map(r => s""""${r.id.value}"""").mkString(",")
    val unavailableIds = unavailable.map(r => s""""${r.id.value}"""").mkString(",")
    s"""{"event":"BookingAvailabilityCheckFailed","itemIds":[$itemIds],"date":"$date","unavailableItems":[$unavailableIds]}"""
