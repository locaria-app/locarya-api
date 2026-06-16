package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.AvailabilityService
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemRepository
}
import java.time.LocalDate
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AvailabilityServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId = ProviderId.generate
  private val date       = LocalDate.of(2026, 9, 1)
  private val price      = Money.fromAmount(BigDecimal("50.00")).toOption.get

  private case class Ctx(
    svc:         AvailabilityService[IO],
    itemRepo:    InMemoryItemRepository[IO],
    comboRepo:   InMemoryComboRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      itemRepo    <- InMemoryItemRepository.make[IO]
      comboRepo   <- InMemoryComboRepository.make[IO]
      bookingRepo <- InMemoryBookingRepository.make[IO]
      svc          = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
    yield Ctx(svc, itemRepo, comboRepo, bookingRepo)

  private def makeItem(ctx: Ctx, stock: Int, pid: ProviderId = providerId): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = pid,
      name                 = "Cadeira",
      description          = "",
      dailyRate            = price,
      stock                = stock,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.itemRepo.create(item).map(_ => item)

  private def bookingFor(
    items:     List[BookingItem],
    onDate:    LocalDate = date,
    status:    BookingStatus = BookingStatus.Confirmed
  ): Booking =
    Booking.create(
      id              = BookingId.generate,
      providerId      = providerId,
      customerId      = CustomerId.generate,
      items           = items,
      startDate       = onDate,
      endDate         = onDate,
      totalAmount     = price,
      status          = status
    ).toOption.get

  // ── Tracer bullet ────────────────────────────────────────────────────────────

  test("individual Item with sufficient stock and no bookings is available") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 5)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      assert(result.available, s"Expected available=true but got: $result")
      assertEquals(result.unavailableItems, List.empty[UnavailableItem])
  }

  // ── Stock consumption from confirmed bookings ────────────────────────────────

  test("individual Item is unavailable when confirmed booking consumes all stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      booking  = bookingFor(List(BookedIndividualItem(item.id, 1)))
      _       <- ctx.bookingRepo.create(booking)
      result  <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      assert(!result.available, s"Expected available=false but got: $result")
      assertEquals(result.unavailableItems.map(_.itemId), List(item.id))
      assertEquals(result.unavailableItems.map(_.reason), List[AvailabilityReason](AvailabilityReason.StockDepleted))
  }

  // ── Status filter ───────────────────────────────────────────────────────────

  test("Pending bookings do not reduce stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      pendingBooking = bookingFor(
                         List(BookedIndividualItem(item.id, 1)),
                         status = BookingStatus.Pending
                       )
      _      <- ctx.bookingRepo.create(pendingBooking)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assert(result.available, s"Expected available=true (Pending must not block) but got: $result")
  }

  test("Cancelled bookings do not reduce stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      cancelled = bookingFor(
                    List(BookedIndividualItem(item.id, 1)),
                    status = BookingStatus.Cancelled
                  )
      _      <- ctx.bookingRepo.create(cancelled)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assert(result.available, s"Expected available=true (Cancelled must not block) but got: $result")
  }

  test("InProgress bookings DO reduce stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      inProgress = bookingFor(
                     List(BookedIndividualItem(item.id, 1)),
                     status = BookingStatus.InProgress
                   )
      _      <- ctx.bookingRepo.create(inProgress)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assert(!result.available, s"Expected available=false (InProgress must block) but got: $result")
  }

  // ── excludeBookingId — editing an existing booking ──────────────────────────

  test("excludeBookingId restores capacity reserved by that booking") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      booking  = bookingFor(List(BookedIndividualItem(item.id, 1)))
      _       <- ctx.bookingRepo.create(booking)
      result  <- ctx.svc.checkAvailability(List((item.id, 1)), date, Some(booking.id))
    yield assert(result.available, s"Expected available=true when excluding the only blocking booking: $result")
  }

  test("excludeBookingId does not affect other bookings on the same date") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      ownBooking = bookingFor(List(BookedIndividualItem(item.id, 1)))
      otherBooking = bookingFor(List(BookedIndividualItem(item.id, 1)))
      _       <- ctx.bookingRepo.create(ownBooking)
      _       <- ctx.bookingRepo.create(otherBooking)
      result  <- ctx.svc.checkAvailability(List((item.id, 1)), date, Some(ownBooking.id))
    yield assert(!result.available, s"Expected available=false — another booking still blocks: $result")
  }

  // ── Combo decomposition ────────────────────────────────────────────────────

  private def makeCombo(
    ctx:           Ctx,
    compositions:  List[ComboItemDefinition]
  ): IO[Combo] =
    val combo = Combo.create(
      id                   = ComboId.generate,
      providerId           = providerId,
      name                 = "Kit Festa",
      description          = "",
      dailyRate            = price,
      items                = compositions,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.comboRepo.create(combo).map(_ => combo)

  test("Combo with all constituents in stock is available") {
    for
      ctx       <- makeCtx
      itemA     <- makeItem(ctx, stock = 5)
      itemB     <- makeItem(ctx, stock = 3)
      combo     <- makeCombo(ctx, List(
                     ComboItemDefinition(itemA.id, 2),
                     ComboItemDefinition(itemB.id, 1)
                   ))
      asItemId   = ItemId.fromString(combo.id.value).toOption.get
      result    <- ctx.svc.checkAvailability(List((asItemId, 1)), date, None)
    yield
      assert(result.available, s"Expected combo available=true but got: $result")
      assertEquals(result.unavailableItems, List.empty[UnavailableItem])
  }

  // ── Structured logging ─────────────────────────────────────────────────────

  test("emits AvailabilityChecked structured log with itemIds, date, result") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      item         <- {
                       val i = Item.create(ItemId.generate, providerId, "X", "", price, 3, AttendantRequirement.NotAllowed).toOption.get
                       itemRepo.create(i).map(_ => i)
                     }
      _            <- svc.checkAvailability(List((item.id, 1)), date, None)
      logs         <- loggerAndGet._2
    yield
      assert(
        logs.exists(l =>
          l.contains("AvailabilityChecked") &&
          l.contains(item.id.value) &&
          l.contains(date.toString) &&
          l.contains("\"available\":true")
        ),
        s"Expected AvailabilityChecked log with itemId/date/available=true. Got: $logs"
      )
  }

  test("AvailabilityChecked log includes reasons when unavailable") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      item         <- {
                       val i = Item.create(ItemId.generate, providerId, "X", "", price, 1, AttendantRequirement.NotAllowed).toOption.get
                       itemRepo.create(i).map(_ => i)
                     }
      booking       = Booking.create(
                        id          = BookingId.generate,
                        providerId  = providerId,
                        customerId  = CustomerId.generate,
                        items       = List(BookedIndividualItem(item.id, 1)),
                        startDate   = date,
                        endDate     = date,
                        totalAmount = price,
                        status      = BookingStatus.Confirmed
                      ).toOption.get
      _            <- bookingRepo.create(booking)
      _            <- svc.checkAvailability(List((item.id, 1)), date, None)
      logs         <- loggerAndGet._2
    yield
      assert(
        logs.exists(l =>
          l.contains("AvailabilityChecked") &&
          l.contains("\"available\":false") &&
          l.contains("stock depleted")
        ),
        s"Expected AvailabilityChecked log with reason='stock depleted'. Got: $logs"
      )
  }

  test("A confirmed BookedCombo consumes its constituent items' stock") {
    for
      ctx       <- makeCtx
      itemA     <- makeItem(ctx, stock = 1)
      itemB     <- makeItem(ctx, stock = 5)
      combo     <- makeCombo(ctx, List(
                     ComboItemDefinition(itemA.id, 1),
                     ComboItemDefinition(itemB.id, 1)
                   ))
      // Confirmed booking of the combo eats itemA's only unit for that date.
      booking    = bookingFor(List(BookedCombo(combo.id, 1)))
      _         <- ctx.bookingRepo.create(booking)
      // Now ask for itemA directly on the same date — should be unavailable.
      result    <- ctx.svc.checkAvailability(List((itemA.id, 1)), date, None)
    yield
      assert(!result.available, s"Expected itemA unavailable after combo consumed it: $result")
      assertEquals(result.unavailableItems.map(_.itemId), List(itemA.id))
  }

  test("Combo is unavailable when ANY constituent Item is out of stock") {
    for
      ctx       <- makeCtx
      itemA     <- makeItem(ctx, stock = 1)
      itemB     <- makeItem(ctx, stock = 5)
      combo     <- makeCombo(ctx, List(
                     ComboItemDefinition(itemA.id, 1),
                     ComboItemDefinition(itemB.id, 1)
                   ))
      // Consume itemA's only unit via a confirmed booking
      booking    = bookingFor(List(BookedIndividualItem(itemA.id, 1)))
      _         <- ctx.bookingRepo.create(booking)
      asItemId   = ItemId.fromString(combo.id.value).toOption.get
      result    <- ctx.svc.checkAvailability(List((asItemId, 1)), date, None)
    yield
      assert(!result.available, s"Expected combo available=false but got: $result")
      assertEquals(result.unavailableItems.map(_.itemId), List(asItemId))
      assertEquals(
        result.unavailableItems.map(_.reason),
        List[AvailabilityReason](AvailabilityReason.ComboItemMissing)
      )
  }
