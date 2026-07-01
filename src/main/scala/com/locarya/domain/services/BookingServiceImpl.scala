package com.locarya.domain.services

import cats.effect.Sync
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import java.time.{Instant, LocalDate}
import org.typelevel.log4cats.Logger

class BookingServiceImpl[F[_]: Sync: Logger](
  providerRepo:  ProviderRepository[F],
  customerRepo:  CustomerRepository[F],
  bookingRepo:   BookingRepository[F],
  itemRepo:      ItemRepository[F],
  comboRepo:     ComboRepository[F],
  availability:  AvailabilityService[F],
  attendantRepo: AttendantRepository[F],
  notifRepo:     NotificationEventRepository[F]
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
      code       = BookingCode.generate
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
                       createdBy       = BookingCreator.Customer,
                       bookingCode     = code,
                       partyProfile    = request.partyProfile
                     )
                   )
      stored    <- bookingRepo.create(booking)
      _         <- Logger[F].info(bookingCreatedLog(stored, customer))
    yield BookingCreated(stored.id, stored.status, stored.totalAmount, stored.bookingCode)

  def createBookingByProvider(providerId: ProviderId, request: CreateBookingByProviderRequest): F[BookingCreated] =
    for
      _         <- requireProvider(providerId)
      _         <- requireAvailable(request.items, request.date)
      customer  <- upsertCustomer(request.customer)
      lines     <- request.items.traverse(resolveLine)
      total     <- totalAmount(lines)
      code       = BookingCode.generate
      booking   <- liftValidation(
                     Booking.create(
                       id              = BookingId.generate,
                       providerId      = providerId,
                       customerId      = customer.id,
                       items           = lines.map(_.item),
                       startDate       = request.date,
                       endDate         = request.date,
                       totalAmount     = total,
                       status          = BookingStatus.Confirmed,
                       deliveryAddress = Some(request.deliveryAddress),
                       createdBy       = BookingCreator.Provider,
                       bookingCode     = code
                     )
                   )
      stored    <- bookingRepo.create(booking)
      _         <- Logger[F].info(bookingCreatedLog(stored, customer))
    yield BookingCreated(stored.id, stored.status, stored.totalAmount, stored.bookingCode)

  def updateBookingStatus(
    providerId: ProviderId,
    bookingId:  BookingId,
    newStatus:  BookingStatus,
    reason:     Option[String]
  ): F[Booking] =
    for
      booking <- bookingRepo.findById(bookingId).flatMap {
                   case Some(b) if b.providerId == providerId => b.pure[F]
                   case _                                     => BookingError.BookingNotFound(bookingId).raiseError[F, Booking]
                 }
      updated <- liftValidation(booking.transitionStatus(newStatus))
      _       <- requireAttendantsWhenConfirming(booking, newStatus)
      stored  <- bookingRepo.update(updated)
      _       <- Logger[F].info(bookingStatusChangedLog(stored, booking.status, reason))
      _       <- enqueueStatusChangedIfNeeded(booking.status, stored)
    yield stored

  private def enqueueStatusChangedIfNeeded(previousStatus: BookingStatus, stored: Booking): F[Unit] =
    if stored.status == BookingStatus.Confirmed || stored.status == BookingStatus.Cancelled then
      Sync[F].delay(Instant.now()).flatMap { now =>
        val payload = s"""{"bookingId":"${stored.id.value}","previousStatus":"${toKebab(previousStatus.toString)}","newStatus":"${toKebab(stored.status.toString)}"}"""
        val event   = NotificationEvent.create(NotificationEventId.generate, "BookingStatusChanged", payload, now)
        notifRepo.create(event).void
      }
    else ().pure[F]

  private def requireAttendantsWhenConfirming(booking: Booking, newStatus: BookingStatus): F[Unit] =
    if newStatus != BookingStatus.Confirmed then ().pure[F]
    else
      booking.items.traverse {
        case BookedIndividualItem(itemId, _, _) =>
          itemRepo.findById(itemId).map(_.exists(_.attendantRequirement == AttendantRequirement.Required))
        case BookedCombo(comboId, _, _) =>
          comboRepo.findById(comboId).map(_.exists(_.attendantRequirement == AttendantRequirement.Required))
      }.flatMap { checks =>
        if checks.exists(identity) then
          attendantRepo.findByBooking(booking.id).flatMap { attendants =>
            if attendants.isEmpty then
              BookingError.InvalidInput(
                InvalidBooking("Booking with required-attendant items must have an attendant assigned before confirmation")
              ).raiseError[F, Unit]
            else ().pure[F]
          }
        else ().pure[F]
      }

  def listBookings(providerId: ProviderId, status: Option[BookingStatus], dateFrom: Option[LocalDate], dateTo: Option[LocalDate]): F[List[DashboardBookingView]] =
    for
      bookings   <- bookingRepo.findByProvider(providerId, status, dateFrom, dateTo)
      customerIds = bookings.map(_.customerId).distinct
      customers  <- customerRepo.findByIds(customerIds)
      views      <- bookings.traverse(toBookingView(_, customers))
    yield views

  private def toBookingView(booking: Booking, customers: Map[CustomerId, Customer]): F[DashboardBookingView] =
    customers.get(booking.customerId) match
      case Some(c) =>
        DashboardBookingView(
          id              = booking.id,
          providerId      = booking.providerId,
          customer        = DashboardCustomerView(c.name, c.email.value, c.phone),
          items           = booking.items,
          date            = booking.startDate,
          deliveryAddress = booking.deliveryAddress,
          status          = booking.status,
          totalAmount     = booking.totalAmount,
          createdBy       = booking.createdBy,
          bookingCode     = booking.bookingCode
        ).pure[F]
      case None =>
        BookingError.InvalidInput(InvalidBooking("Customer not found")).raiseError[F, DashboardBookingView]

  private def requireProvider(slug: StorefrontSlug): F[Provider] =
    providerRepo.findBySlug(slug).flatMap {
      case Some(p) => p.pure[F]
      case None    => BookingError.ProviderNotFound(slug).raiseError[F, Provider]
    }

  private def requireProvider(id: ProviderId): F[Provider] =
    providerRepo.findById(id).flatMap {
      case Some(p) => p.pure[F]
      case None    => BookingError.ProviderIdNotFound(id).raiseError[F, Provider]
    }

  private def requireAvailable(request: CreateBookingRequest): F[Unit] =
    requireAvailable(request.items, request.date)

  private def requireAvailable(items: List[BookingLineInput], date: LocalDate): F[Unit] =
    val lines = items.map(l => (l.itemId, l.quantity))
    availability.checkAvailability(lines, date, None).flatMap { results =>
      val unavailable = results.filterNot(_.available)
      if unavailable.isEmpty then ().pure[F]
      else
        Logger[F].info(availabilityFailedLog(date, results, unavailable)) *>
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

  private def bookingStatusChangedLog(booking: Booking, fromStatus: BookingStatus, reason: Option[String]): String =
    val reasonPart = reason.map(r => s""","reason":"$r"""").getOrElse("")
    s"""{"event":"BookingStatusChanged","bookingId":"${booking.id.value}","fromStatus":"${toKebab(fromStatus.toString)}","toStatus":"${toKebab(booking.status.toString)}"$reasonPart}"""

  private def toKebab(s: String): String =
    s.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase

  private def availabilityFailedLog(
    date:        LocalDate,
    results:     List[ItemAvailability],
    unavailable: List[ItemAvailability]
  ): String =
    val itemIds        = results.map(r => s""""${r.id.value}"""").mkString(",")
    val unavailableIds = unavailable.map(r => s""""${r.id.value}"""").mkString(",")
    s"""{"event":"BookingAvailabilityCheckFailed","itemIds":[$itemIds],"date":"$date","unavailableItems":[$unavailableIds]}"""
