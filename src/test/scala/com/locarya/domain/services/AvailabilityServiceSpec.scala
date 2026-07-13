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
      requiresMonitor = false
    ).toOption.get
    ctx.itemRepo.create(item).as(item)

  private def makeCombo(ctx: Ctx, items: List[(Item, Int)]): IO[Combo] =
    val combo = Combo.create(
      id                   = ComboId.generate,
      providerId           = providerId,
      name                 = "Combo Festa",
      description          = "Combo de festa",
      dailyRate            = price,
      items                = items.map((i, q) => ComboItemDefinition(i.id, q)),
      requiresMonitor = false
    ).toOption.get
    ctx.comboRepo.create(combo).as(combo)

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
      createdAt   = java.time.Instant.EPOCH,
      status      = status
    ).toOption.get
    ctx.bookingRepo.create(booking).as(booking)

  private def makeInactiveItem(ctx: Ctx, stock: Int = 5): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = providerId,
      name                 = "Item Desativado",
      description          = "Removido do catálogo",
      dailyRate            = price,
      stock                = stock,
      requiresMonitor = false,
      isActive             = false
    ).toOption.get
    ctx.itemRepo.create(item).as(item)

  private def comboAsItemId(combo: Combo): ItemId =
    ItemId.fromString(combo.id.value).toOption.get

  extension (results: List[ItemAvailability])
    private def forId(id: ItemId): ItemAvailability =
      results.find(_.id == id).getOrElse(fail(s"no availability entry for ${id.value}"))

  // ── Individual Items ──────────────────────────────────────────────────────────

  test("available Item with sufficient stock reports kind=Item, available=true, availableQty=stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 3)
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      val r = results.forId(item.id)
      assertEquals(r.kind, AvailabilityKind.Item)
      assertEquals(r.available, true)
      assertEquals(r.availableQty, 3)
  }

  test("requested qty exceeding stock makes the Item unavailable but still reports remaining qty") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      results <- ctx.svc.checkAvailability(List((item.id, 2)), date, None)
    yield
      val r = results.forId(item.id)
      assertEquals(r.available, false)
      assertEquals(r.availableQty, 1)
  }

  test("a Confirmed booking consumes stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 2)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 2)))
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      val r = results.forId(item.id)
      assertEquals(r.available, false)
      assertEquals(r.availableQty, 0)
  }

  test("an InProgress booking consumes stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 2)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 2)), status = BookingStatus.InProgress)
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(results.forId(item.id).available, false)
  }

  test("Pending and Cancelled bookings do not consume stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), status = BookingStatus.Pending)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), status = BookingStatus.Cancelled)
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      val r = results.forId(item.id)
      assertEquals(r.available, true)
      assertEquals(r.availableQty, 1)
  }

  test("excludeBookingId ignores the contribution of one booking") {
    for
      ctx       <- makeCtx
      item      <- makeItem(ctx, stock = 1)
      bookingId  = BookingId.generate
      _         <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), bookingId = bookingId)
      withExcl  <- ctx.svc.checkAvailability(List((item.id, 1)), date, Some(bookingId))
      without   <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      assertEquals(withExcl.forId(item.id).available, true)
      assertEquals(without.forId(item.id).available, false)
  }

  test("a booking on another date does not consume stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)), on = date.plusDays(1))
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(results.forId(item.id).available, true)
  }

  test("an unknown id reports kind=Unknown, available=false, availableQty=0") {
    for
      ctx     <- makeCtx
      unknown  = ItemId.generate
      results <- ctx.svc.checkAvailability(List((unknown, 1)), date, None)
    yield
      val r = results.forId(unknown)
      assertEquals(r.kind, AvailabilityKind.Unknown)
      assertEquals(r.available, false)
      assertEquals(r.availableQty, 0)
  }

  test("results preserve request order for a mixed batch") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 1)
      b       <- makeItem(ctx, stock = 1)
      results <- ctx.svc.checkAvailability(List((b.id, 1), (a.id, 1)), date, None)
    yield assertEquals(results.map(_.id), List(b.id, a.id))
  }

  // ── Combos ────────────────────────────────────────────────────────────────────

  test("a Combo with all constituents in stock is available; availableQty = min fulfillable") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 4)
      b       <- makeItem(ctx, stock = 6)
      combo   <- makeCombo(ctx, List((a, 1), (b, 2)))
      results <- ctx.svc.checkAvailability(List((comboAsItemId(combo), 1)), date, None)
    yield
      val r = results.forId(comboAsItemId(combo))
      assertEquals(r.kind, AvailabilityKind.Combo)
      assertEquals(r.available, true)
      // a supports 4 combos, b supports 6/2 = 3 → min = 3
      assertEquals(r.availableQty, 3)
  }

  test("a Combo is unavailable when one constituent is short, while that constituent stays independently scoped") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 0)
      b       <- makeItem(ctx, stock = 5)
      c       <- makeItem(ctx, stock = 5)
      combo   <- makeCombo(ctx, List((a, 1), (b, 1), (c, 1)))
      results <- ctx.svc.checkAvailability(
                   List((comboAsItemId(combo), 1), (b.id, 1), (c.id, 1)),
                   date,
                   None
                 )
    yield
      assertEquals(results.forId(comboAsItemId(combo)).available, false)
      assertEquals(results.forId(comboAsItemId(combo)).availableQty, 0)
      // B and C are independently available even though the combo is not
      assertEquals(results.forId(b.id).available, true)
      assertEquals(results.forId(c.id).available, true)
  }

  test("a Confirmed booking of a Combo consumes the items underneath") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 1)
      b       <- makeItem(ctx, stock = 1)
      combo   <- makeCombo(ctx, List((a, 1), (b, 1)))
      _       <- makeBooking(ctx, List(BookedCombo(combo.id, 1)))
      results <- ctx.svc.checkAvailability(List((a.id, 1), (b.id, 1)), date, None)
    yield
      assertEquals(results.forId(a.id).available, false)
      assertEquals(results.forId(b.id).available, false)
  }

  test("a Combo's constituent stock is consumed proportionally to the booked combo quantity") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 5)
      combo   <- makeCombo(ctx, List((a, 2)))
      _       <- makeBooking(ctx, List(BookedCombo(combo.id, 1))) // consumes 2 of a
      results <- ctx.svc.checkAvailability(List((comboAsItemId(combo), 1)), date, None)
    yield
      // remaining a = 3 → 3/2 = 1 full combo
      assertEquals(results.forId(comboAsItemId(combo)).availableQty, 1)
      assertEquals(results.forId(comboAsItemId(combo)).available, true)
  }

  test("availableQty never reports negative for an overbooked item") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)))
      _       <- makeBooking(ctx, List(BookedIndividualItem(item.id, 1)))
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield assertEquals(results.forId(item.id).availableQty, 0)
  }

  // ── Within-request aggregation ─────────────────────────────────────────────────

  test("duplicate requested ids compete for the same stock") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 1)
      results <- ctx.svc.checkAvailability(List((item.id, 1), (item.id, 1)), date, None)
    yield
      // 2 units demanded, only 1 in stock → neither entry fits
      assertEquals(results.count(_.available), 0)
      // availableQty stays the standalone remaining stock
      assert(results.forall(_.availableQty == 1))
  }

  test("sibling entries that together fit are both available") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx, stock = 2)
      results <- ctx.svc.checkAvailability(List((item.id, 1), (item.id, 1)), date, None)
    yield assertEquals(results.count(_.available), 2)
  }

  test("a Combo and one of its constituents requested together compete for that item's stock") {
    for
      ctx     <- makeCtx
      a       <- makeItem(ctx, stock = 1)
      combo   <- makeCombo(ctx, List((a, 1)))
      results <- ctx.svc.checkAvailability(List((comboAsItemId(combo), 1), (a.id, 1)), date, None)
    yield
      // combo needs 1 of a, plus 1 of a requested directly → 2 demanded, 1 in stock
      assertEquals(results.forId(comboAsItemId(combo)).available, false)
      assertEquals(results.forId(a.id).available, false)
  }

  // ── Inactive (soft-deleted) ids ────────────────────────────────────────────────

  test("an inactive Item requested by id is not bookable (kind=Unknown)") {
    for
      ctx     <- makeCtx
      item    <- makeInactiveItem(ctx, stock = 5)
      results <- ctx.svc.checkAvailability(List((item.id, 1)), date, None)
    yield
      val r = results.forId(item.id)
      assertEquals(r.kind, AvailabilityKind.Unknown)
      assertEquals(r.available, false)
  }

  test("a Combo with a deactivated constituent is unavailable") {
    for
      ctx     <- makeCtx
      active  <- makeItem(ctx, stock = 5)
      gone    <- makeInactiveItem(ctx, stock = 5)
      combo   <- makeCombo(ctx, List((active, 1), (gone, 1)))
      results <- ctx.svc.checkAvailability(List((comboAsItemId(combo), 1)), date, None)
    yield assertEquals(results.forId(comboAsItemId(combo)).available, false)
  }

  // ── Logging ─────────────────────────────────────────────────────────────────

  test("emits a single AvailabilityChecked log line per call") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      itemRepo          <- InMemoryItemRepository.make[IO]
      comboRepo         <- InMemoryComboRepository.make[IO]
      bookingRepo       <- InMemoryBookingRepository.make[IO]
      svc                = { given Logger[IO] = logger; AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo) }
      item               = Item.create(ItemId.generate, providerId, "X", "d", price, 1, false).toOption.get
      _                 <- itemRepo.create(item)
      _                 <- svc.checkAvailability(List((item.id, 1)), date, None)
      logs              <- getLogs
    yield
      assertEquals(logs.size, 1)
      assert(logs.head.contains("\"event\":\"AvailabilityChecked\""))
      assert(logs.head.contains("\"available\":true"))
  }
