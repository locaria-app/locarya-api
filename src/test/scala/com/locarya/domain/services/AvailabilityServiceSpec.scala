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
  private val customerId = CustomerId.generate
  private val date       = LocalDate.of(2026, 9, 1)
  private val price      = Money.fromAmount(BigDecimal("100.00")).toOption.get

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

  private def makeItem(ctx: Ctx, stock: Int = 1): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = providerId,
      name                 = "Cama Elástica",
      description          = "Para festa",
      dailyRate            = price,
      stock                = stock,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.itemRepo.create(item).map(_ => item)

  private def makeCombo(ctx: Ctx, items: List[(Item, Int)]): IO[Combo] =
    val combo = Combo.create(
      id                   = ComboId.generate,
      providerId           = providerId,
      name                 = "Combo Festa",
      description          = "Combo de festa",
      dailyRate            = price,
      items                = items.map((i, q) => ComboItemDefinition(i.id, q)),
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.comboRepo.create(combo).map(_ => combo)

  private def makeBooking(
    ctx:       Ctx,
    items:     List[BookingItem],
    status:    BookingStatus = BookingStatus.Confirmed,
    bookingId: BookingId     = BookingId.generate,
    on:        LocalDate     = date
  ): IO[Booking] =
    val booking = Booking.create(
      id          = bookingId,
      providerId  = providerId,
      customerId  = customerId,
      items       = items,
      startDate   = on,
      endDate     = on,
      totalAmount = price,
      status      = status
    ).toOption.get
    ctx.bookingRepo.create(booking).map(_ => booking)

  private def itemIdOfCombo(combo: Combo): ItemId =
    ItemId.fromString(combo.id.value).toOption.get

  // ── Individual Items ──────────────────────────────────────────────────────────

  test("checkAvailability returns available for an Item with sufficient stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 2)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      assertEquals(result.available, true)
      assertEquals(result.unavailableItems, List.empty)
  }

  test("checkAvailability returns unavailable when requested qty exceeds stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      result <- ctx.svc.checkAvailability(List((item.id, 2)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.map(_.itemId), List(item.id))
      assertEquals(result.unavailableItems.head.reason, "stock depleted")
  }

  // ── Bookings consume stock ────────────────────────────────────────────────────

  test("checkAvailability counts a Confirmed Booking on the same date as consuming stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      _      <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Confirmed)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.map(_.itemId), List(item.id))
      assertEquals(result.unavailableItems.head.reason, "stock depleted")
  }

  test("checkAvailability counts an InProgress Booking as consuming stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      // Reach InProgress only via the valid Pending → Confirmed → InProgress chain
      booking = Booking.create(
                  id          = BookingId.generate,
                  providerId  = providerId,
                  customerId  = customerId,
                  items       = List(BookedIndividualItem(item.id, 1)),
                  startDate   = date,
                  endDate     = date,
                  totalAmount = price,
                  status      = BookingStatus.InProgress
                ).toOption.get
      _      <- ctx.bookingRepo.create(booking)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(result.available, false)
  }

  test("checkAvailability ignores a Pending Booking — does not consume stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      _      <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Pending)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(result.available, true)
  }

  test("checkAvailability ignores a Cancelled Booking — does not consume stock") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      _      <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Cancelled)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(result.available, true)
  }

  test("checkAvailability ignores a Confirmed Booking on a different date") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, stock = 1)
      other   = LocalDate.of(2026, 12, 25)
      _      <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Confirmed, on = other)
      result <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(result.available, true)
  }

  // ── Combos ───────────────────────────────────────────────────────────────────

  test("checkAvailability for a Combo whose constituent Items all have stock returns available") {
    for
      ctx    <- makeCtx
      a      <- makeItem(ctx, stock = 1)
      b      <- makeItem(ctx, stock = 1)
      combo  <- makeCombo(ctx, List((a, 1), (b, 1)))
      result <- ctx.svc.checkAvailability(List((itemIdOfCombo(combo), 1)), date, None)
    yield assertEquals(result.available, true)
  }

  test("checkAvailability for a Combo with one short constituent Item returns unavailable") {
    for
      ctx    <- makeCtx
      a      <- makeItem(ctx, stock = 1)
      b      <- makeItem(ctx, stock = 0)
      combo  <- makeCombo(ctx, List((a, 1), (b, 1)))
      result <- ctx.svc.checkAvailability(List((itemIdOfCombo(combo), 1)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.map(_.itemId), List(itemIdOfCombo(combo)))
      assertEquals(result.unavailableItems.head.reason, "combo item missing")
  }

  test("checkAvailability for a Combo whose constituent Item is consumed by a Confirmed Booking returns unavailable") {
    for
      ctx    <- makeCtx
      a      <- makeItem(ctx, stock = 1)
      b      <- makeItem(ctx, stock = 1)
      combo  <- makeCombo(ctx, List((a, 1), (b, 1)))
      _      <- makeBooking(ctx, List(BookedIndividualItem(b.id, 1)), BookingStatus.Confirmed)
      result <- ctx.svc.checkAvailability(List((itemIdOfCombo(combo), 1)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.head.reason, "combo item missing")
  }

  test("checkAvailability counts a Confirmed BookedCombo as consuming its constituent Items") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 1)
      combo   <- makeCombo(ctx, List((a, 1)))
      _       <- makeBooking(ctx, List(BookedCombo(combo.id, 1)), BookingStatus.Confirmed)
      // Now request Item A directly — it should be unavailable because the combo booking used it
      result  <- ctx.svc.checkAvailability(List((a.id, 1)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.map(_.itemId), List(a.id))
  }

  // ── excludeBookingId ─────────────────────────────────────────────────────────

  test("checkAvailability with excludeBookingId ignores the excluded Booking's consumption") {
    for
      ctx       <- makeCtx
      item      <- makeItem(ctx, stock = 1)
      bookingId  = BookingId.generate
      _         <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Confirmed, bookingId = bookingId)
      result    <- ctx.svc.checkAvailability(List((item.id, 1)), date, Some(bookingId))
    yield assertEquals(result.available, true)
  }

  test("checkAvailability with excludeBookingId still counts other Confirmed Bookings") {
    for
      ctx       <- makeCtx
      item      <- makeItem(ctx, stock = 1)
      bookingId  = BookingId.generate
      _         <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Confirmed, bookingId = bookingId)
      _         <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), BookingStatus.Confirmed)
      result    <- ctx.svc.checkAvailability(List((item.id, 1)), date, Some(bookingId))
    yield assertEquals(result.available, false)
  }

  // ── Multi-item ───────────────────────────────────────────────────────────────

  test("checkAvailability with multiple Items lists every unavailable Item") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 1)
      b       <- makeItem(ctx, stock = 0)
      c       <- makeItem(ctx, stock = 0)
      result  <- ctx.svc.checkAvailability(List((a.id, 1), (b.id, 1), (c.id, 1)), date, None)
    yield
      assertEquals(result.available, false)
      assertEquals(result.unavailableItems.map(_.itemId).toSet, Set(b.id, c.id))
  }

  // ── Structured log ───────────────────────────────────────────────────────────

  test("checkAvailability emits an AvailabilityChecked structured log line") {
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
      _            <- svc.checkAvailability(List((item.id, 1)), date, None)
      logs         <- loggerAndGet._2
    yield assert(
      logs.exists(l => l.contains("AvailabilityChecked") && l.contains(item.id.value) && l.contains(date.toString)),
      s"Expected AvailabilityChecked log mentioning the itemId and date. Got: $logs"
    )
  }

  test("checkAvailability emits a log reason when unavailable") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      item         <- {
                       val i = Item.create(ItemId.generate, providerId, "X", "", price, 0, AttendantRequirement.NotAllowed).toOption.get
                       itemRepo.create(i).map(_ => i)
                     }
      _            <- svc.checkAvailability(List((item.id, 1)), date, None)
      logs         <- loggerAndGet._2
    yield assert(
      logs.exists(l => l.contains("AvailabilityChecked") && l.contains("stock depleted")),
      s"Expected unavailable reason 'stock depleted' in log. Got: $logs"
    )
  }
