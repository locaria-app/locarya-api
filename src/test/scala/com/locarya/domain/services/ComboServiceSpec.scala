package com.locarya.domain.services

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.{ComboService, CreateComboRequest, UpdateComboRequest}
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryBookingRepository,
  InMemoryComboImageRepository,
  InMemoryComboRepository,
  InMemoryItemRepository
}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class ComboServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId  = ProviderId.generate
  private val price       = Money.fromAmount(BigDecimal("200.00")).toOption.get
  private val imageUrl1   = "https://example.com/combo1.jpg"
  private val imageUrl2   = "https://example.com/combo2.jpg"

  private case class Ctx(
    svc:            ComboService[IO],
    itemRepo:       InMemoryItemRepository[IO],
    comboRepo:      InMemoryComboRepository[IO],
    bookingRepo:    InMemoryBookingRepository[IO],
    comboImageRepo: InMemoryComboImageRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      itemRepo       <- InMemoryItemRepository.make[IO]
      comboRepo      <- InMemoryComboRepository.make[IO]
      bookingRepo    <- InMemoryBookingRepository.make[IO]
      comboImageRepo <- InMemoryComboImageRepository.make[IO]
      svc             = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo, comboImageRepo)
    yield Ctx(svc, itemRepo, comboRepo, bookingRepo, comboImageRepo)

  private def makeItem(ctx: Ctx, pid: ProviderId = providerId, requiresMonitor: Boolean = false): IO[Item] =
    val item = Item.create(
      id              = ItemId.generate,
      providerId      = pid,
      name            = "Cadeira",
      description     = "Cadeira de festa",
      dailyRate       = Money.fromAmount(BigDecimal("50.00")).toOption.get,
      stock           = 10,
      requiresMonitor = requiresMonitor
    ).toOption.get
    ctx.itemRepo.create(item).map(_ => item)

  private def validRequest(ctx: Ctx): IO[CreateComboRequest] =
    makeItem(ctx).map { item =>
      CreateComboRequest(
        providerId       = providerId,
        name             = "Kit Festa Completo",
        description      = "Inclui cadeiras e mesas",
        dailyRate        = price,
        itemCompositions = List(ComboItemDefinition(item.id, 5)),
        imageUrls        = List(imageUrl1)
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
                   itemCompositions = List(ComboItemDefinition(item.id, 3)),
                   imageUrls        = List(imageUrl1)
                 )
      comboId <- ctx.svc.createCombo(req)
      comps   <- ctx.comboRepo.findItemsInCombo(comboId)
    yield
      assertEquals(comps.size, 1)
      assertEquals(comps.head.itemId, item.id)
      assertEquals(comps.head.quantity, 3)
  }

  // ── requiresMonitor inference ────────────────────────────────────────────────

  test("createCombo infers requiresMonitor = false when all items have requiresMonitor = false") {
    for
      ctx     <- makeCtx
      item1   <- makeItem(ctx, requiresMonitor = false)
      item2   <- makeItem(ctx, requiresMonitor = false)
      comboId <- ctx.svc.createCombo(CreateComboRequest(
                   providerId       = providerId,
                   name             = "Combo sem Monitor",
                   description      = "Nenhum item exige monitor",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(item1.id, 1), ComboItemDefinition(item2.id, 1)),
                   imageUrls        = List(imageUrl1)
                 ))
      stored  <- ctx.comboRepo.findById(comboId)
    yield assertEquals(stored.map(_.requiresMonitor), Some(false))
  }

  test("createCombo infers requiresMonitor = true when at least one item has requiresMonitor = true") {
    for
      ctx     <- makeCtx
      item1   <- makeItem(ctx, requiresMonitor = false)
      item2   <- makeItem(ctx, requiresMonitor = true)
      comboId <- ctx.svc.createCombo(CreateComboRequest(
                   providerId       = providerId,
                   name             = "Combo Misto",
                   description      = "Um item exige monitor",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(item1.id, 1), ComboItemDefinition(item2.id, 1)),
                   imageUrls        = List(imageUrl1)
                 ))
      stored  <- ctx.comboRepo.findById(comboId)
    yield assertEquals(stored.map(_.requiresMonitor), Some(true))
  }

  test("createCombo infers requiresMonitor = true when all items have requiresMonitor = true") {
    for
      ctx     <- makeCtx
      item1   <- makeItem(ctx, requiresMonitor = true)
      item2   <- makeItem(ctx, requiresMonitor = true)
      comboId <- ctx.svc.createCombo(CreateComboRequest(
                   providerId       = providerId,
                   name             = "Combo com Monitor",
                   description      = "Todos os itens exigem monitor",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(item1.id, 1), ComboItemDefinition(item2.id, 1)),
                   imageUrls        = List(imageUrl1)
                 ))
      stored  <- ctx.comboRepo.findById(comboId)
    yield assertEquals(stored.map(_.requiresMonitor), Some(true))
  }

  test("createCombo with valid imageUrls stores images in comboImageRepo") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req      = CreateComboRequest(
                   providerId       = providerId,
                   name             = "Kit",
                   description      = "Desc",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(item.id, 1)),
                   imageUrls        = List(imageUrl1, imageUrl2)
                 )
      comboId <- ctx.svc.createCombo(req)
      images  <- ctx.comboImageRepo.findByComboId(comboId)
    yield
      assertEquals(images.size, 2)
      assertEquals(images.map(_.imageUrl.value).toSet, Set(imageUrl1, imageUrl2))
  }

  test("createCombo with empty imageUrls raises error") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx)
      req     = CreateComboRequest(
                  providerId       = providerId,
                  name             = "Kit",
                  description      = "Desc",
                  dailyRate        = price,
                  itemCompositions = List(ComboItemDefinition(item.id, 1)),
                  imageUrls        = List.empty
                )
      result <- ctx.svc.createCombo(req).attempt
    yield assert(result.isLeft, "Expected failure for empty imageUrls")
  }

  test("createCombo emits ComboCreated structured log") {
    for
      loggerAndGet   <- CapturingLogger.make
      itemRepo       <- InMemoryItemRepository.make[IO]
      comboRepo      <- InMemoryComboRepository.make[IO]
      bookingRepo    <- InMemoryBookingRepository.make[IO]
      comboImageRepo <- InMemoryComboImageRepository.make[IO]
      svc             = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo, comboImageRepo)(using implicitly, loggerAndGet._1)
      item           <- {
                         val i = Item.create(ItemId.generate, providerId, "X", "", Money.fromAmount(BigDecimal("10")).toOption.get, 1, false).toOption.get
                         itemRepo.create(i).map(_ => i)
                       }
      req             = CreateComboRequest(providerId, "K", "D", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
      _              <- svc.createCombo(req)
      logs           <- loggerAndGet._2
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
                  itemCompositions = List(ComboItemDefinition(item.id, 1)),
                  imageUrls        = List(imageUrl1)
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
                     itemCompositions = List(ComboItemDefinition(unknownId, 1)),
                     imageUrls        = List(imageUrl1)
                   )
      result    <- ctx.svc.createCombo(req).attempt
    yield assert(result.isLeft, "Expected failure for unknown itemId")
  }

  test("createCombo with a comboId used as itemId raises ContainsNestedCombo") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req1     = CreateComboRequest(providerId, "Combo1", "Desc", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
      comboId <- ctx.svc.createCombo(req1)
      nestedItemId = ItemId.fromString(comboId.value).toOption.get
      req2     = CreateComboRequest(
                   providerId       = providerId,
                   name             = "NestedCombo",
                   description      = "Should fail",
                   dailyRate        = price,
                   itemCompositions = List(ComboItemDefinition(nestedItemId, 1)),
                   imageUrls        = List(imageUrl1)
                 )
      result  <- ctx.svc.createCombo(req2).attempt
    yield result match
      case Left(_: ComboError.ContainsNestedCombo) => ()
      case other => fail(s"Expected ContainsNestedCombo but got: $other")
  }

  // ── getCombo ─────────────────────────────────────────────────────────────────

  test("getCombo returns the combo with images for its owner") {
    for
      ctx    <- makeCtx
      req    <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      result  <- ctx.svc.getCombo(comboId, providerId)
      (combo, images) = result
    yield
      assertEquals(combo.id, comboId)
      assertEquals(combo.name, "Kit Festa Completo")
      assertEquals(images.map(_.imageUrl.value), List(imageUrl1))
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
                   itemCompositions = None,
                   imageUrls        = List(imageUrl1)
                 ))
      stored  <- ctx.comboRepo.findById(comboId)
    yield
      assertEquals(stored.map(_.name), Some("Kit Especial"))
      assertEquals(stored.map(_.dailyRate), Some(newPrice))
  }

  test("updateCombo with imageUrls replaces images") {
    for
      ctx             <- makeCtx
      req             <- validRequest(ctx)
      comboId         <- ctx.svc.createCombo(req)
      _               <- ctx.svc.updateCombo(UpdateComboRequest(
                           comboId          = comboId,
                           providerId       = providerId,
                           name             = "Kit",
                           description      = "Desc",
                           dailyRate        = price,
                           itemCompositions = None,
                           imageUrls        = List(imageUrl2)
                         ))
      images          <- ctx.comboImageRepo.findByComboId(comboId)
    yield
      assertEquals(images.size, 1)
      assertEquals(images.head.imageUrl.value, imageUrl2)
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
                   itemCompositions = None,
                   imageUrls        = List(imageUrl1)
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
      req       = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item1.id, 1)), List(imageUrl1))
      comboId  <- ctx.svc.createCombo(req)
      _        <- ctx.svc.updateCombo(UpdateComboRequest(
                    comboId          = comboId,
                    providerId       = providerId,
                    name             = "Kit",
                    description      = "D",
                    dailyRate        = price,
                    itemCompositions = Some(List(ComboItemDefinition(item2.id, 2))),
                    imageUrls        = List(imageUrl1)
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
      req      = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
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
                   itemCompositions = Some(List(ComboItemDefinition(item.id, 2))),
                   imageUrls        = List(imageUrl1)
                 )).attempt
    yield result match
      case Left(_: ComboError.HasBookings) => ()
      case other => fail(s"Expected HasBookings but got: $other")
  }

  test("updateCombo with bookings but no itemCompositions change succeeds") {
    for
      ctx     <- makeCtx
      item    <- makeItem(ctx)
      req      = CreateComboRequest(providerId, "Kit", "D", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
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
                   itemCompositions = None,
                   imageUrls        = List(imageUrl1)
                 )).attempt
    yield assert(result.isRight, s"Expected success but got: $result")
  }

  test("updateCombo with bookings and itemCompositions logs ComboEditBlocked") {
    for
      loggerAndGet   <- CapturingLogger.make
      itemRepo       <- InMemoryItemRepository.make[IO]
      comboRepo      <- InMemoryComboRepository.make[IO]
      bookingRepo    <- InMemoryBookingRepository.make[IO]
      comboImageRepo <- InMemoryComboImageRepository.make[IO]
      svc             = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo, comboImageRepo)(using implicitly, loggerAndGet._1)
      item           <- {
                         val i = Item.create(ItemId.generate, providerId, "X", "", Money.fromAmount(BigDecimal("10")).toOption.get, 1, false).toOption.get
                         itemRepo.create(i).map(_ => i)
                       }
      req             = CreateComboRequest(providerId, "K", "D", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
      comboId        <- svc.createCombo(req)
      booking         = Booking.create(
                          id          = BookingId.generate,
                          providerId  = providerId,
                          customerId  = CustomerId.generate,
                          items       = List(BookedCombo(comboId, 1)),
                          startDate   = java.time.LocalDate.of(2026, 9, 1),
                          endDate     = java.time.LocalDate.of(2026, 9, 3),
                          totalAmount = price
                        ).toOption.get
      _              <- bookingRepo.create(booking)
      _              <- svc.updateCombo(UpdateComboRequest(comboId, providerId, "K", "D", price, Some(List(ComboItemDefinition(item.id, 2))), List(imageUrl1))).attempt
      logs           <- loggerAndGet._2
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

  // ── activateCombo ────────────────────────────────────────────────────────────

  test("activateCombo sets isActive=true on a deactivated combo") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      _       <- ctx.svc.softDeleteCombo(comboId, providerId)
      _       <- ctx.svc.activateCombo(comboId, providerId)
      stored  <- ctx.comboRepo.findById(comboId)
    yield assertEquals(stored.map(_.isActive), Some(true))
  }

  test("activateCombo emits ComboActivated structured log") {
    for
      loggerAndGet   <- CapturingLogger.make
      itemRepo       <- InMemoryItemRepository.make[IO]
      comboRepo      <- InMemoryComboRepository.make[IO]
      bookingRepo    <- InMemoryBookingRepository.make[IO]
      comboImageRepo <- InMemoryComboImageRepository.make[IO]
      svc             = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo, comboImageRepo)(using implicitly, loggerAndGet._1)
      item           <- {
                         val i = Item.create(ItemId.generate, providerId, "X", "", Money.fromAmount(BigDecimal("10")).toOption.get, 1, false).toOption.get
                         itemRepo.create(i).map(_ => i)
                       }
      req             = CreateComboRequest(providerId, "K", "D", price, List(ComboItemDefinition(item.id, 1)), List(imageUrl1))
      comboId        <- svc.createCombo(req)
      _              <- svc.softDeleteCombo(comboId, providerId)
      _              <- svc.activateCombo(comboId, providerId)
      logs           <- loggerAndGet._2
    yield
      assert(logs.exists(l => l.contains("ComboActivated") && l.contains(comboId.value)),
        s"Expected ComboActivated log. Got: $logs")
  }

  test("activateCombo for nonexistent combo raises NotFound") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.activateCombo(ComboId.generate, providerId).attempt
    yield result match
      case Left(_: ComboError.NotFound) => ()
      case other => fail(s"Expected NotFound but got: $other")
  }

  test("activateCombo by wrong provider raises Forbidden") {
    val otherId = ProviderId.generate
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      _       <- ctx.svc.softDeleteCombo(comboId, providerId)
      result  <- ctx.svc.activateCombo(comboId, otherId).attempt
    yield result match
      case Left(_: ComboError.Forbidden) => ()
      case other => fail(s"Expected Forbidden but got: $other")
  }

  // ── listCombos ───────────────────────────────────────────────────────────────

  test("listCombos returns empty list when no combos exist for the provider") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.listCombos(providerId)
    yield assertEquals(result, List.empty)
  }

  test("listCombos returns only the provider's own combos") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      _       <- ctx.svc.createCombo(req)
      _       <- ctx.svc.createCombo(req)
      result  <- ctx.svc.listCombos(providerId)
    yield assertEquals(result.size, 2)
  }

  test("listCombos returns inactive (soft-deleted) combos, marked isActive=false") {
    for
      ctx     <- makeCtx
      req     <- validRequest(ctx)
      comboId <- ctx.svc.createCombo(req)
      _       <- ctx.svc.softDeleteCombo(comboId, providerId)
      result  <- ctx.svc.listCombos(providerId)
    yield
      assertEquals(result.size, 1)
      assertEquals(result.head._1.isActive, false)
  }

  test("listCombos does not return combos belonging to a different provider") {
    val otherProvider = ProviderId.generate
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx, otherProvider)
      req     = CreateComboRequest(
                  providerId       = otherProvider,
                  name             = "Kit Outro",
                  description      = "Desc",
                  dailyRate        = price,
                  itemCompositions = List(ComboItemDefinition(item.id, 1)),
                  imageUrls        = List(imageUrl1)
                )
      _      <- ctx.svc.createCombo(req)
      result <- ctx.svc.listCombos(providerId)
    yield assertEquals(result, List.empty)
  }

  test("listCombos returns combos with their images") {
    for
      ctx    <- makeCtx
      item   <- makeItem(ctx)
      req     = CreateComboRequest(
                  providerId       = providerId,
                  name             = "Kit",
                  description      = "Desc",
                  dailyRate        = price,
                  itemCompositions = List(ComboItemDefinition(item.id, 1)),
                  imageUrls        = List(imageUrl1, imageUrl2)
                )
      _      <- ctx.svc.createCombo(req)
      result <- ctx.svc.listCombos(providerId)
    yield
      assertEquals(result.size, 1)
      val (_, images) = result.head
      assertEquals(images.map(_.imageUrl.value).toSet, Set(imageUrl1, imageUrl2))
  }
