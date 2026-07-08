package com.locarya.domain.services

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.{CreateItemRequest, ItemService, UpdateItemRequest}
import com.locarya.helpers.{CapturingLogger, InMemoryBookingRepository, InMemoryItemImageRepository, InMemoryItemRepository}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class ItemServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId = ProviderId.generate
  private val price      = Money.fromAmount(BigDecimal("150.00")).toOption.get
  private val url1       = "https://example.com/img1.jpg"
  private val url2       = "https://example.com/img2.jpg"

  private case class Ctx(
    svc:         ItemService[IO],
    itemRepo:    InMemoryItemRepository[IO],
    imageRepo:   InMemoryItemImageRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      itemRepo    <- InMemoryItemRepository.make[IO]
      imageRepo   <- InMemoryItemImageRepository.make[IO]
      bookingRepo <- InMemoryBookingRepository.make[IO]
      svc          = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)
    yield Ctx(svc, itemRepo, imageRepo, bookingRepo)

  private val validRequest = CreateItemRequest(
    providerId      = providerId,
    name            = "Cama Elástica",
    description     = "Cama elástica infantil",
    dailyRate       = price,
    stock           = 3,
    requiresMonitor = true,
    imageUrls       = List(url1, url2)
  )

  // ── createItem happy path ────────────────────────────────────────────────────

  test("createItem with valid request returns ItemId") {
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
    yield assert(itemId.value.nonEmpty)
  }

  test("createItem stores item as active with provided fields") {
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      stored <- ctx.itemRepo.findById(itemId)
    yield
      assert(stored.isDefined, "Item should be stored")
      stored.foreach { item =>
        assertEquals(item.name, "Cama Elástica")
        assertEquals(item.stock, 3)
        assertEquals(item.dailyRate, price)
        assertEquals(item.isActive, true)
        assertEquals(item.requiresMonitor, true)
      }
  }

  test("createItem stores images linked to the item") {
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      images <- ctx.imageRepo.findByItemId(itemId)
    yield
      assertEquals(images.size, 2)
      assertEquals(images.count(_.isPrimary), 1)
      assertEquals(images.head.isPrimary, true)
  }

  test("createItem emits ItemCreated structured log") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      _            <- svc.createItem(validRequest)
      logs         <- loggerAndGet._2
    yield
      assert(logs.exists(l => l.contains("ItemCreated") && l.contains(providerId.value)),
        s"Expected ItemCreated log. Got: $logs")
  }

  // ── createItem validation ────────────────────────────────────────────────────

  test("createItem with stock = 0 raises ItemError") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.createItem(validRequest.copy(stock = 0)).attempt
    yield assert(result.isLeft, "Expected failure for stock = 0")
  }

  test("createItem with empty name raises ItemError") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.createItem(validRequest.copy(name = "")).attempt
    yield assert(result.isLeft, "Expected failure for empty name")
  }

  test("createItem with no imageUrls raises ItemError") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.createItem(validRequest.copy(imageUrls = List.empty)).attempt
    yield assert(result.isLeft, "Expected failure for no imageUrls")
  }

  // ── updateItem ───────────────────────────────────────────────────────────────

  test("updateItem changes name and replaces images") {
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      _      <- ctx.svc.updateItem(UpdateItemRequest(
                  itemId          = itemId,
                  providerId      = providerId,
                  name            = "Mesa Dobrável",
                  description     = "Mesa de festa",
                  dailyRate       = price,
                  stock           = 5,
                  requiresMonitor = false,
                  imageUrls       = List(url2)
                ))
      stored <- ctx.itemRepo.findById(itemId)
      images <- ctx.imageRepo.findByItemId(itemId)
    yield
      assertEquals(stored.map(_.name), Some("Mesa Dobrável"))
      assertEquals(stored.map(_.stock), Some(5))
      assertEquals(images.size, 1)
      assertEquals(images.head.imageUrl.value, url2)
  }

  test("updateItem by a different provider raises ItemError") {
    val otherId = ProviderId.generate
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      result <- ctx.svc.updateItem(UpdateItemRequest(
                   itemId          = itemId,
                   providerId      = otherId,
                   name            = "Hack",
                   description     = "",
                   dailyRate       = price,
                   stock           = 1,
                   requiresMonitor = false,
                   imageUrls       = List(url1)
                 )).attempt
    yield assert(result.isLeft, "Expected failure for wrong provider")
  }

  // ── deactivateItem ───────────────────────────────────────────────────────────

  test("deactivateItem soft-deletes item — it no longer appears in listActiveItems") {
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      _      <- ctx.svc.deactivateItem(itemId, providerId)
      pairs  <- ctx.svc.listActiveItems(providerId)
    yield assert(!pairs.exists(_._1.id == itemId), "Deactivated item should not appear in active list")
  }

  test("deactivateItem emits ItemDeactivated structured log") {
    for
      loggerAndGet <- CapturingLogger.make
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      svc           = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)(using implicitly, loggerAndGet._1)
      itemId       <- svc.createItem(validRequest)
      _            <- svc.deactivateItem(itemId, providerId)
      logs         <- loggerAndGet._2
    yield
      assert(logs.exists(l => l.contains("ItemDeactivated") && l.contains(itemId.value)),
        s"Expected ItemDeactivated log. Got: $logs")
  }

  test("deactivateItem when item has bookings raises ItemError.HasBookings") {
    for
      ctx     <- makeCtx
      itemId  <- ctx.svc.createItem(validRequest)
      booking  = Booking.create(
                   id = BookingId.generate,
                   providerId = providerId,
                   customerId = CustomerId.generate,
                   items = List(BookedIndividualItem(itemId, 1)),
                   startDate = java.time.LocalDate.of(2026, 8, 1),
                   endDate = java.time.LocalDate.of(2026, 8, 3),
                   totalAmount = price
                 ).toOption.get
      _       <- ctx.bookingRepo.create(booking)
      result  <- ctx.svc.deactivateItem(itemId, providerId).attempt
    yield result match
      case Left(ItemError.HasBookings(_)) => ()
      case other => fail(s"Expected ItemError.HasBookings but got: $other")
  }

  test("deactivateItem by a different provider raises ItemError") {
    val otherId = ProviderId.generate
    for
      ctx    <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      result <- ctx.svc.deactivateItem(itemId, otherId).attempt
    yield assert(result.isLeft, "Expected failure for wrong provider")
  }

  // ── listActiveItems ──────────────────────────────────────────────────────────

  test("listActiveItems returns only active items for the provider") {
    for
      ctx   <- makeCtx
      id1   <- ctx.svc.createItem(validRequest)
      id2   <- ctx.svc.createItem(validRequest.copy(name = "Item 2"))
      _     <- ctx.svc.deactivateItem(id2, providerId)
      pairs <- ctx.svc.listActiveItems(providerId)
    yield
      assert(pairs.exists(_._1.id == id1), "Active item should be listed")
      assert(!pairs.exists(_._1.id == id2), "Deactivated item should not be listed")
  }

  test("listActiveItems does not return items from another provider") {
    val otherProvider = ProviderId.generate
    for
      ctx   <- makeCtx
      _     <- ctx.svc.createItem(validRequest)
      _     <- ctx.svc.createItem(validRequest.copy(providerId = otherProvider))
      pairs <- ctx.svc.listActiveItems(providerId)
    yield assert(pairs.forall(_._1.providerId == providerId))
  }

  test("listActiveItems returns item paired with its two images ordered by displayOrder") {
    for
      ctx   <- makeCtx
      itemId <- ctx.svc.createItem(validRequest)
      pairs  <- ctx.svc.listActiveItems(providerId)
    yield
      val (item, images) = pairs.find(_._1.id == itemId).get
      assertEquals(item.id, itemId)
      assertEquals(images.size, 2)
      assertEquals(images.map(_.displayOrder), images.sortBy(_.displayOrder).map(_.displayOrder))
  }

  test("listActiveItems returns Nil images for an item seeded without images") {
    for
      ctx    <- makeCtx
      item   <- cats.effect.IO.fromEither(
                  Item.create(
                    id              = ItemId.generate,
                    providerId      = providerId,
                    name            = "No Images Item",
                    description     = "desc",
                    dailyRate       = price,
                    stock           = 1,
                    requiresMonitor = false
                  ).left.map(e => new RuntimeException(e.toString))
                )
      _      <- ctx.itemRepo.create(item)
      pairs  <- ctx.svc.listActiveItems(providerId)
    yield
      val (_, images) = pairs.find(_._1.id == item.id).get
      assertEquals(images, Nil)
  }

  test("listActiveItems does not mix images across items") {
    for
      ctx    <- makeCtx
      itemId1 <- ctx.svc.createItem(validRequest.copy(imageUrls = List(url1)))
      itemId2 <- ctx.svc.createItem(validRequest.copy(name = "Item 2", imageUrls = List(url2)))
      pairs   <- ctx.svc.listActiveItems(providerId)
    yield
      val (_, imgs1) = pairs.find(_._1.id == itemId1).get
      val (_, imgs2) = pairs.find(_._1.id == itemId2).get
      assert(imgs1.forall(_.imageUrl.value == url1), "Item1 should only have url1")
      assert(imgs2.forall(_.imageUrl.value == url2), "Item2 should only have url2")
  }
