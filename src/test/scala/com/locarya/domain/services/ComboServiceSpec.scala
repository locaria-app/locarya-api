package com.locarya.domain.services

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.{ComboService, CreateComboRequest, UpdateComboRequest}
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemRepository
}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class ComboServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId = ProviderId.generate
  private val price      = Money.fromAmount(BigDecimal("200.00")).toOption.get

  private case class Ctx(
    svc:         ComboService[IO],
    itemRepo:    InMemoryItemRepository[IO],
    comboRepo:   InMemoryComboRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      itemRepo    <- InMemoryItemRepository.make[IO]
      comboRepo   <- InMemoryComboRepository.make[IO]
      bookingRepo <- InMemoryBookingRepository.make[IO]
      svc          = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)
    yield Ctx(svc, itemRepo, comboRepo, bookingRepo)

  private def makeItem(ctx: Ctx, pid: ProviderId = providerId): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = pid,
      name                 = "Cadeira",
      description          = "Cadeira de festa",
      dailyRate            = Money.fromAmount(BigDecimal("50.00")).toOption.get,
      stock                = 10,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.itemRepo.create(item).map(_ => item)

  private def validRequest(ctx: Ctx): IO[CreateComboRequest] =
    makeItem(ctx).map { item =>
      CreateComboRequest(
        providerId       = providerId,
        name             = "Kit Festa Completo",
        description      = "Inclui cadeiras e mesas",
        dailyRate        = price,
        itemCompositions = List(ComboItemDefinition(item.id, 5))
      )
    }

  // ── createCombo happy path ───────────────────────────────────────────────────

  test("createCombo with valid request returns ComboId") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
    yield assert(comboId.value.nonEmpty)
  }

  test("createCombo stores combo as active with provided fields") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      stored  <- ctx.comboRepo.findById(comboId)
    yield
      assert(stored.isDefined, "Combo should be stored")
      stored.foreach { combo =>
        assertEquals(combo.name, "Kit Festa Completo")
        assertEquals(combo.dailyRate, price)
        assertEquals(combo.isActive, true)
        assertEquals(combo.providerId, providerId)
      }
  }

  test("createCombo stores combo_items records") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req      = CreateComboRequest(
                   providerId       = providerId,
                   name             = "Kit",
                   description      = "Desc",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(item.id, 3))
                 )
      comboId <- ctx.svc.createCombo(req)
      comps   <- ctx.comboRepo.findItemsInCombo(comboId)
    yield
      assertEquals(comps.size, 1)
      assertEquals(comps.head.itemId, item.id)
      assertEquals(comps.head.quantity, 3)
  }

  test("createCombo emits ComboCreated structured log") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      item         <- {
                       val i = Item.create(ItemId.generate, providerId, "X", "", Money.fromAmount(BigDecimal("10")).toOption.get, 1, AttendantRequirement.NotAllowed).toOption.get
                       itemRepo.create(i).map(_ => i)
                     }
      req           = CreateComboRequest(providerId, "K", "D", price, List(ComboItemDefinition(item.id, 1)))
      _            <- svc.createCombo(req)
      logs         <- loggerAndGet._2
    yield
      assert(logs.exists(l => l.contains("ComboCreated") && l.contains(providerId.value)),
        s"Expected ComboCreated log. Got: $logs")
  }

  // ── createCombo validation ───────────────────────────────────────────────────

  test("createCombo with empty name raises ComboError") {
    for
      ctx    <- makeCtx
      req    <- validRequest(ctx)
      result <- ctx.svc.createCombo(req.copy(name = "")).attempt
    yield assert(result.isLeft, "Expected failure for empty name")
  }

  test("createCombo with empty itemCompositions raises ComboError") {
    for
      ctx    <- makeCtx
      req    <- validRequest(ctx)
      result <- ctx.svc.createCombo(req.copy(itemCompositions = List.empty)).attempt
    yield assert(result.isLeft, "Expected failure for empty composition")
  }

  test("createCombo with item from a different provider raises ItemBelongsToDifferentProvider") {
    val otherProvider = ProviderId.generate
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, otherProvider)
      req     = CreateComboRequest(
                  providerId       = providerId,
                  name             = "Kit",
                  description      = "Desc",
                  dailyRate        = price,
                  itemCompositions = List(ComboItemDefinition(item.id, 1))
                )
      result <- ctx.svc.createCombo(req).attempt
    yield result match
      case Left(_: ComboError.ItemBelongsToDifferentProvider) => ()
      case other => fail(s"Expected ItemBelongsToDifferentProvider but got: $other")
  }

  test("createCombo with unknown itemId raises ItemNotFound") {
    for
      ctx       <- makeCtx
      unknownId  = ItemId.generate
      req        = CreateComboRequest(
                     providerId       = providerId,
                     name             = "Kit",
                     description      = "Desc",
                     dailyRate        = price,
                     itemCompositions = List(ComboItemDefinition(unknownId, 1))
                   )
      result    <- ctx.svc.createCombo(req).attempt
    yield assert(result.isLeft, "Expected failure for unknown itemId")
  }

  test("createCombo with a comboId used as itemId raises ContainsNestedCombo") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      // Create a combo first
      req1     = CreateComboRequest(providerId, "Combo1", "Desc", price, List(ComboItemDefinition(item.id, 1)))
      comboId <- ctx.svc.createCombo(req1)
      // Try to create a combo that references the first combo's id as if it were an item
      nestedItemId = ItemId.fromString(comboId.value).toOption.get
      req2     = CreateComboRequest(
                   providerId       = providerId,
                   name             = "NestedCombo",
                   description      = "Should fail",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(nestedItemId, 1))
                 )
      result  <- ctx.svc.createCombo(req2).attempt
    yield result match
      case Left(_: ComboError.ContainsNestedCombo) => ()
      case other => fail(s"Expected ContainsNestedCombo but got: $other")
  }

  // ── getCombo ─────────────────────────────────────────────────────────────────

  test("getCombo returns the combo for its owner") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      combo   <- ctx.svc.getCombo(comboId, providerId)
    yield
      assertEquals(combo.id, comboId)
      assertEquals(combo.name, "Kit Festa Completo")
  }

  test("getCombo for wrong provider raises Forbidden") {
    val otherId = ProviderId.generate
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      result  <- ctx.svc.getCombo(comboId, otherId).attempt
    yield result match
      case Left(_: ComboError.Forbidden) => ()
      case other => fail(s"Expected Forbidden but got: $other")
  }

  test("getCombo for nonexistent combo raises NotFound") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.getCombo(ComboId.generate, providerId).attempt
    yield result match
      case Left(_: ComboError.NotFound) => ()
      case other => fail(s"Expected NotFound but got: $other")
  }

  // ── updateCombo ──────────────────────────────────────────────────────────────

  test("updateCombo changes name and price") {
    val newPrice = Money.fromAmount(BigDecimal("350.00")).toOption.get
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      _       <- ctx.svc.updateCombo(UpdateComboRequest(
                   comboId          = comboId,
                   providerId       = providerId,
                   name             = "Kit Especial",
                   description      = "Nova descrição",
                   dailyRate        = newPrice,
                   itemCompositions = None
                 ))
      stored  <- ctx.comboRepo.findById(comboId)
    yield
      assertEquals(stored.map(_.name), Some("Kit Especial"))
      assertEquals(stored.map(_.dailyRate), Some(newPrice))
  }

  test("updateCombo by wrong provider raises Forbidden") {
    val otherId = ProviderId.generate
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      result  <- ctx.svc.updateCombo(UpdateComboRequest(
                   comboId          = comboId,
                   providerId       = otherId,
                   name             = "Hack",
                   description      = "",
                   dailyRate        = price,
                   itemCompositions = None
                 )).attempt
    yield result match
      case Left(_: ComboError.Forbidden) => ()
      case other => fail(s"Expected Forbidden but got: $other")
  }

  test("updateCombo with itemCompositions and no bookings succeeds") {
    for
      ctx      <- makeCtx
      item1    <- makeItem(ctx)
      item2    <- makeItem(ctx)
      req       = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item1.id, 1)))
      comboId  <- ctx.svc.createCombo(req)
      _        <- ctx.svc.updateCombo(UpdateComboRequest(
                    comboId          = comboId,
                    providerId       = providerId,
                    name             = "Kit",
                    description      = "D",
                    dailyRate        = price,
                    itemCompositions = Some(List(ComboItemDefinition(item2.id, 2)))
                  ))
      comps    <- ctx.comboRepo.findItemsInCombo(comboId)
    yield
      assertEquals(comps.size, 1)
      assertEquals(comps.head.itemId, item2.id)
  }

  test("updateCombo with itemCompositions when bookings exist raises HasBookings") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req      = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item.id, 1)))
      comboId <- ctx.svc.createCombo(req)
      booking  = Booking.create(
                   id          = BookingId.generate,
                   providerId  = providerId,
                   customerId  = CustomerId.generate,
                   items       = List(BookedCombo(comboId, 1)),
                   startDate   = java.time.LocalDate.of(2026, 9, 1),
                   endDate     = java.time.LocalDate.of(2026, 9, 3),
                   totalAmount = price
                 ).toOption.get
      _       <- ctx.bookingRepo.create(booking)
      result  <- ctx.svc.updateCombo(UpdateComboRequest(
                   comboId          = comboId,
                   providerId       = providerId,
                   name             = "Kit",
                   description      = "D",
                   dailyRate        = price,
                   itemCompositions = Some(List(ComboItemDefinition(item.id, 2)))
                 )).attempt
    yield result match
      case Left(_: ComboError.HasBookings) => ()
      case other => fail(s"Expected HasBookings but got: $other")
  }

  test("updateCombo with bookings but no itemCompositions change succeeds") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req      = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item.id, 1)))
      comboId <- ctx.svc.createCombo(req)
      booking  = Booking.create(
                   id          = BookingId.generate,
                   providerId  = providerId,
                   customerId  = CustomerId.generate,
                   items       = List(BookedCombo(comboId, 1)),
                   startDate   = java.time.LocalDate.of(2026, 9, 1),
                   endDate     = java.time.LocalDate.of(2026, 9, 3),
                   totalAmount = price
                 ).toOption.get
      _       <- ctx.bookingRepo.create(booking)
      result  <- ctx.svc.updateCombo(UpdateComboRequest(
                   comboId          = comboId,
                   providerId       = providerId,
                   name             = "Kit Atualizado",
                   description      = "Nova desc",
                   dailyRate        = price,
                   itemCompositions = None
                 )).attempt
    yield assert(result.isRight, s"Expected success but got: $result")
  }

  test("updateCombo with bookings and itemCompositions logs ComboEditBlocked") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      item         <- {
                       val i = Item.create(ItemId.generate, providerId, "X", "", Money.fromAmount(BigDecimal("10")).toOption.get, 1, AttendantRequirement.NotAllowed).toOption.get
                       itemRepo.create(i).map(_ => i)
                     }
      req           = CreateComboRequest(providerId, "K", "D", price, List(ComboItemDefinition(item.id, 1)))
      comboId      <- svc.createCombo(req)
      booking       = Booking.create(
                        id          = BookingId.generate,
                        providerId  = providerId,
                        customerId  = CustomerId.generate,
                        items       = List(BookedCombo(comboId, 1)),
                        startDate   = java.time.LocalDate.of(2026, 9, 1),
                        endDate     = java.time.LocalDate.of(2026, 9, 3),
                        totalAmount = price
                      ).toOption.get
      _            <- bookingRepo.create(booking)
      _            <- svc.updateCombo(UpdateComboRequest(comboId, providerId, "K", "D", price, Some(List(ComboItemDefinition(item.id, 2))))).attempt
      logs         <- loggerAndGet._2
    yield
      assert(logs.exists(l => l.contains("ComboEditBlocked") && l.contains(comboId.value)),
        s"Expected ComboEditBlocked log. Got: $logs")
  }

  // ── softDeleteCombo ──────────────────────────────────────────────────────────

  test("softDeleteCombo sets isActive=false") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      _       <- ctx.svc.softDeleteCombo(comboId, providerId)
      stored  <- ctx.comboRepo.findById(comboId)
    yield assertEquals(stored.map(_.isActive), Some(false))
  }

  test("softDeleteCombo by wrong provider raises Forbidden") {
    val otherId = ProviderId.generate
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      result  <- ctx.svc.softDeleteCombo(comboId, otherId).attempt
    yield result match
      case Left(_: ComboError.Forbidden) => ()
      case other => fail(s"Expected Forbidden but got: $other")
  }

  test("softDeleteCombo for nonexistent combo raises NotFound") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.softDeleteCombo(ComboId.generate, providerId).attempt
    yield result match
      case Left(_: ComboError.NotFound) => ()
      case other => fail(s"Expected NotFound but got: $other")
  }
