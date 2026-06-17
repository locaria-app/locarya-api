package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryCustomerRepository,
  InMemoryItemRepository,
  InMemoryProviderRepository
}
import java.time.LocalDate
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class BookingServiceSpec extends CatsEffectSuite:

  private val date  = LocalDate.of(2026, 9, 1)
  private val price = Money.fromAmount(BigDecimal("100.00")).toOption.get
  private val slug  = StorefrontSlug.fromString("loja-test").toOption.get

  private val deliveryAddress =
    Address.create("Rua A", "100", "Centro", "São Paulo", "SP", "01000-000", None).toOption.get

  private val customerInput =
    CustomerInput(name = "Maria Festa", email = Email.fromString("maria@example.com").toOption.get, phone = Some("11999990000"))

  /** Availability stub: every requested id is available unless listed in `unavailableIds`. */
  private final class StubAvailabilityService(unavailableIds: Set[ItemId]) extends AvailabilityService[IO]:
    def checkAvailability(
      items:            List[(ItemId, Int)],
      date:             LocalDate,
      excludeBookingId: Option[BookingId]
    ): IO[List[ItemAvailability]] =
      IO.pure(items.map { case (id, qty) =>
        val ok = !unavailableIds.contains(id)
        ItemAvailability(id, AvailabilityKind.Item, ok, if ok then qty else 0)
      })

  private case class Ctx(
    svc:          BookingService[IO],
    providerRepo: InMemoryProviderRepository[IO],
    customerRepo: InMemoryCustomerRepository[IO],
    bookingRepo:  InMemoryBookingRepository[IO],
    itemRepo:     InMemoryItemRepository[IO],
    provider:     Provider
  )

  private def makeCtx(
    unavailableIds: Set[ItemId] = Set.empty,
    logger:         Logger[IO]  = NoOpLogger[IO]
  ): IO[Ctx] =
    given Logger[IO] = logger
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      customerRepo <- InMemoryCustomerRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      itemRepo     <- InMemoryItemRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      provider      = Provider.create(
                        id           = ProviderId.generate,
                        email        = Email.fromString("locador@example.com").toOption.get,
                        taxId        = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                        businessName = "Locador LTDA",
                        tradeName    = "Locador",
                        city         = "São Paulo",
                        state        = "SP",
                        storefrontSlug = slug
                      ).toOption.get
      _            <- providerRepo.create(provider)
      availability  = StubAvailabilityService(unavailableIds)
      svc           = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availability)
    yield Ctx(svc, providerRepo, customerRepo, bookingRepo, itemRepo, provider)

  private def buildItem(ctx: Ctx, id: ItemId, stock: Int, itemPrice: Money): Item =
    Item.create(
      id                   = id,
      providerId           = ctx.provider.id,
      name                 = "Cama Elástica",
      description          = "Para festa",
      dailyRate            = itemPrice,
      stock                = stock,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get

  private def seedItem(ctx: Ctx, stock: Int = 5, itemPrice: Money = price): IO[Item] =
    val item = buildItem(ctx, ItemId.generate, stock, itemPrice)
    ctx.itemRepo.create(item).as(item)

  private def seedItemWithId(ctx: Ctx, id: ItemId, stock: Int = 5, itemPrice: Money = price): IO[Item] =
    val item = buildItem(ctx, id, stock, itemPrice)
    ctx.itemRepo.create(item).as(item)

  private def request(items: List[(ItemId, Int)], customer: CustomerInput = customerInput): CreateBookingRequest =
    CreateBookingRequest(
      slug            = slug,
      items           = items.map((id, qty) => BookingLineInput(id, qty)),
      date            = date,
      deliveryAddress = deliveryAddress,
      customer        = customer
    )

  test("creates a Pending booking created by the customer when all items are available") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      created <- ctx.svc.createBooking(request(List((item.id, 1))))
      stored  <- ctx.bookingRepo.findById(created.bookingId)
    yield
      assertEquals(created.status, BookingStatus.Pending)
      assert(stored.isDefined, "Expected the booking to be persisted")
      assertEquals(stored.get.status, BookingStatus.Pending)
      assertEquals(stored.get.createdBy, BookingCreator.Customer)
      assertEquals(stored.get.providerId, ctx.provider.id)
  }

  test("persists exactly one booking when all items are available") {
    for
      ctx      <- makeCtx()
      item     <- seedItem(ctx)
      _        <- ctx.svc.createBooking(request(List((item.id, 1))))
      pending  <- ctx.bookingRepo.findByStatus(BookingStatus.Pending)
    yield assertEquals(pending.size, 1)
  }

  test("raises ItemsUnavailable and persists no booking when an item is unavailable") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      // mark the item unavailable in a fresh ctx that knows the id
      ctx2    <- makeCtx(unavailableIds = Set(item.id))
      i2      <- seedItemWithId(ctx2, item.id)
      result  <- ctx2.svc.createBooking(request(List((item.id, 1)))).attempt
      stored  <- ctx2.bookingRepo.findByStatus(BookingStatus.Pending)
    yield
      assert(result.isLeft, "Expected createBooking to fail when an item is unavailable")
      result.left.foreach {
        case e: BookingError.ItemsUnavailable => assert(e.unavailable.exists(_.id == item.id))
        case other                            => fail(s"Expected ItemsUnavailable, got $other")
      }
      assert(stored.isEmpty, "Expected no booking to be persisted on availability failure")
  }

  test("creates a new Customer by email when none exists") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      before  <- ctx.customerRepo.findByEmail(customerInput.email)
      _       <- ctx.svc.createBooking(request(List((item.id, 1))))
      after   <- ctx.customerRepo.findByEmail(customerInput.email)
    yield
      assertEquals(before, None)
      assert(after.isDefined, "Expected a new Customer to be created")
      assertEquals(after.get.phone, customerInput.phone)
      assertEquals(after.get.cpf, None)
  }

  test("reuses the existing Customer when one already has the email") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      existing   = Customer.create(CustomerId.generate, customerInput.email, name = "Maria Antiga").toOption.get
      _         <- ctx.customerRepo.create(existing)
      created   <- ctx.svc.createBooking(request(List((item.id, 1))))
      stored    <- ctx.bookingRepo.findById(created.bookingId)
    yield assertEquals(stored.get.customerId, existing.id)
  }

  test("snapshots the unit price and total from the item price at creation time") {
    for
      ctx      <- makeCtx()
      item     <- seedItem(ctx, itemPrice = Money.fromAmount(BigDecimal("100.00")).toOption.get)
      created  <- ctx.svc.createBooking(request(List((item.id, 2))))
      // raise the item price AFTER the booking is created
      repriced  = buildItem(ctx, item.id, stock = 5, Money.fromAmount(BigDecimal("150.00")).toOption.get)
      _        <- ctx.itemRepo.update(repriced)
      stored   <- ctx.bookingRepo.findById(created.bookingId)
    yield
      assertEquals(stored.get.totalAmount.amount, BigDecimal("200.00"))
      val line = stored.get.items.collectFirst { case b: BookedIndividualItem => b }.get
      assertEquals(line.unitPrice.map(_.amount), Some(BigDecimal("100.00")))
  }

  test("emits a single BookingCreated log line on success") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      ctx               <- makeCtx(logger = logger)
      item              <- seedItem(ctx)
      created           <- ctx.svc.createBooking(request(List((item.id, 1))))
      logs              <- getLogs
    yield
      assertEquals(logs.size, 1)
      assert(logs.head.contains("\"event\":\"BookingCreated\""), logs.head)
      assert(logs.head.contains(created.bookingId.value), logs.head)
      assert(logs.head.contains("\"createdBy\":\"customer\""), logs.head)
  }

  test("emits BookingAvailabilityCheckFailed with the unavailable item id when validation fails") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      ctx               <- makeCtx(unavailableIds = Set.empty, logger = logger)
      item              <- seedItem(ctx)
      ctxU              <- makeCtx(unavailableIds = Set(item.id), logger = logger)
      _                 <- seedItemWithId(ctxU, item.id)
      _                 <- ctxU.svc.createBooking(request(List((item.id, 1)))).attempt
      logs              <- getLogs
    yield
      val failLine = logs.find(_.contains("\"event\":\"BookingAvailabilityCheckFailed\""))
      assert(failLine.isDefined, s"Expected a BookingAvailabilityCheckFailed log line, got: $logs")
      assert(failLine.get.contains(item.id.value), failLine.get)
  }
