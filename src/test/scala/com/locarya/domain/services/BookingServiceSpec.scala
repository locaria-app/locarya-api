package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryAttendantRepository,
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryCustomerRepository,
  InMemoryItemRepository,
  InMemoryNotificationEventRepository,
  InMemoryProviderRepository
}
import com.locarya.domain.services.AvailabilityServiceImpl
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
    svc:           BookingService[IO],
    providerRepo:  InMemoryProviderRepository[IO],
    customerRepo:  InMemoryCustomerRepository[IO],
    bookingRepo:   InMemoryBookingRepository[IO],
    itemRepo:      InMemoryItemRepository[IO],
    attendantRepo: InMemoryAttendantRepository[IO],
    notifRepo:     InMemoryNotificationEventRepository[IO],
    provider:      Provider
  )

  private def makeCtx(
    unavailableIds: Set[ItemId] = Set.empty,
    logger:         Logger[IO]  = NoOpLogger[IO]
  ): IO[Ctx] =
    given Logger[IO] = logger
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      customerRepo  <- InMemoryCustomerRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      itemRepo      <- InMemoryItemRepository.make[IO]
      comboRepo     <- InMemoryComboRepository.make[IO]
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      notifRepo     <- InMemoryNotificationEventRepository.make[IO]
      provider       = Provider.create(
                         id             = ProviderId.generate,
                         email          = Email.fromString("locador@example.com").toOption.get,
                         taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                         businessName   = "Locador LTDA",
                         tradeName      = "Locador",
                         city           = "São Paulo",
                         state          = "SP",
                         storefrontSlug = slug
                       ).toOption.get
      _             <- providerRepo.create(provider)
      availability   = StubAvailabilityService(unavailableIds)
      svc            = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availability, attendantRepo, notifRepo)
    yield Ctx(svc, providerRepo, customerRepo, bookingRepo, itemRepo, attendantRepo, notifRepo, provider)

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

  private val bookingCodePattern = """^LCR-[A-Z0-9]{6}$""".r

  test("createBooking result carries a bookingCode matching LCR-[A-Z0-9]{6}") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      created <- ctx.svc.createBooking(request(List((item.id, 1))))
    yield
      assert(
        bookingCodePattern.matches(created.bookingCode.value),
        s"Expected bookingCode matching LCR-[A-Z0-9]{6}, got '${created.bookingCode.value}'"
      )
  }

  test("createBooking persists the bookingCode on the stored Booking") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      created <- ctx.svc.createBooking(request(List((item.id, 1))))
      stored  <- ctx.bookingRepo.findById(created.bookingId)
    yield
      assert(stored.isDefined, "Expected booking to be persisted")
      assertEquals(stored.get.bookingCode.value, created.bookingCode.value)
  }

  test("two consecutive createBooking calls produce different bookingCodes") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx, stock = 10)
      c1      <- ctx.svc.createBooking(request(List((item.id, 1))))
      c2      <- ctx.svc.createBooking(request(List((item.id, 1))))
    yield
      assertNotEquals(c1.bookingCode.value, c2.bookingCode.value,
        "Expected consecutive bookings to have distinct booking codes")
  }

  test("createBookingByProvider result carries a bookingCode matching LCR-[A-Z0-9]{6}") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      created <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
    yield
      assert(
        bookingCodePattern.matches(created.bookingCode.value),
        s"Expected bookingCode matching LCR-[A-Z0-9]{6}, got '${created.bookingCode.value}'"
      )
  }

  test("listBookings view carries a bookingCode matching LCR-[A-Z0-9]{6}") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      created <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      list    <- ctx.svc.listBookings(ctx.provider.id, None, None, None)
    yield
      assertEquals(list.size, 1)
      assert(
        bookingCodePattern.matches(list.head.bookingCode.value),
        s"Expected bookingCode in list view, got '${list.head.bookingCode.value}'"
      )
      assertEquals(list.head.bookingCode.value, created.bookingCode.value)
  }

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

  // ── Provider-created bookings ──

  test("createBookingByProvider creates a Confirmed booking with createdBy=Provider") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      created <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      stored  <- ctx.bookingRepo.findById(created.bookingId)
    yield
      assertEquals(created.status, BookingStatus.Confirmed)
      assert(stored.isDefined, "Expected the booking to be persisted")
      assertEquals(stored.get.status, BookingStatus.Confirmed)
      assertEquals(stored.get.createdBy, BookingCreator.Provider)
  }

  test("createBookingByProvider validates availability before persisting") {
    for
      ctx     <- makeCtx(unavailableIds = Set.empty)
      item    <- seedItem(ctx)
      ctx2    <- makeCtx(unavailableIds = Set(item.id))
      _       <- seedItemWithId(ctx2, item.id)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      result  <- ctx2.svc.createBookingByProvider(ctx2.provider.id, req).attempt
      stored  <- ctx2.bookingRepo.findByStatus(BookingStatus.Confirmed)
    yield
      assert(result.isLeft, "Expected createBookingByProvider to fail when item is unavailable")
      result.left.foreach {
        case e: BookingError.ItemsUnavailable => assert(e.unavailable.exists(_.id == item.id))
        case other                            => fail(s"Expected ItemsUnavailable, got $other")
      }
      assert(stored.isEmpty, "Expected no booking to be persisted on availability failure")
  }

  test("createBookingByProvider emits BookingCreated log with createdBy=provider") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      ctx               <- makeCtx(logger = logger)
      item              <- seedItem(ctx)
      req                = CreateBookingByProviderRequest(
                             items           = List(BookingLineInput(item.id, 1)),
                             date            = date,
                             deliveryAddress = deliveryAddress,
                             customer        = customerInput
                           )
      created           <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      logs              <- getLogs
    yield
      assertEquals(logs.size, 1)
      assert(logs.head.contains("\"event\":\"BookingCreated\""), logs.head)
      assert(logs.head.contains(created.bookingId.value), logs.head)
      assert(logs.head.contains("\"createdBy\":\"provider\""), logs.head)
  }

  test("listBookings returns bookings for the provider") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      created <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      list    <- ctx.svc.listBookings(ctx.provider.id, None, None, None)
    yield
      assertEquals(list.size, 1)
      assertEquals(list.head.id, created.bookingId)
      assertEquals(list.head.status, BookingStatus.Confirmed)
  }

  test("listBookings filters by status") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      _       <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      pending <- ctx.svc.listBookings(ctx.provider.id, Some(BookingStatus.Pending), None, None)
      confirmed <- ctx.svc.listBookings(ctx.provider.id, Some(BookingStatus.Confirmed), None, None)
    yield
      assertEquals(pending.size, 0)
      assertEquals(confirmed.size, 1)
  }

  test("listBookings filters by date range") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      yesterday  = date.minusDays(1)
      tomorrow   = date.plusDays(1)
      req        = CreateBookingByProviderRequest(
                     items           = List(BookingLineInput(item.id, 1)),
                     date            = date,
                     deliveryAddress = deliveryAddress,
                     customer        = customerInput
                   )
      _         <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      inRange   <- ctx.svc.listBookings(ctx.provider.id, None, Some(yesterday), Some(tomorrow))
      outOfRange <- ctx.svc.listBookings(ctx.provider.id, None, Some(tomorrow), Some(tomorrow))
    yield
      assertEquals(inRange.size, 1)
      assertEquals(outOfRange.size, 0)
  }

  test("listBookings includes denormalized customer data") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      req      = CreateBookingByProviderRequest(
                   items           = List(BookingLineInput(item.id, 1)),
                   date            = date,
                   deliveryAddress = deliveryAddress,
                   customer        = customerInput
                 )
      _       <- ctx.svc.createBookingByProvider(ctx.provider.id, req)
      list    <- ctx.svc.listBookings(ctx.provider.id, None, None, None)
    yield
      assertEquals(list.head.customer.name, customerInput.name)
      assertEquals(list.head.customer.email, customerInput.email.value)
      assertEquals(list.head.customer.phone, customerInput.phone)
  }

  test("listBookings does not return bookings belonging to a different provider") {
    given Logger[IO] = NoOpLogger[IO]
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      customerRepo  <- InMemoryCustomerRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      itemRepo      <- InMemoryItemRepository.make[IO]
      comboRepo     <- InMemoryComboRepository.make[IO]
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      notifRepo     <- InMemoryNotificationEventRepository.make[IO]
      svc            = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, StubAvailabilityService(Set.empty), attendantRepo, notifRepo)
      prov1         = Provider.create(
                        id             = ProviderId.generate,
                        email          = Email.fromString("p1@test.com").toOption.get,
                        taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                        businessName   = "Prov One LTDA",
                        tradeName      = "Prov One",
                        city           = "São Paulo",
                        state          = "SP",
                        storefrontSlug = StorefrontSlug.fromString("prov-one").toOption.get
                      ).toOption.get
      prov2         = Provider.create(
                        id             = ProviderId.generate,
                        email          = Email.fromString("p2@test.com").toOption.get,
                        taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                        businessName   = "Prov Two LTDA",
                        tradeName      = "Prov Two",
                        city           = "Rio de Janeiro",
                        state          = "RJ",
                        storefrontSlug = StorefrontSlug.fromString("prov-two").toOption.get
                      ).toOption.get
      _            <- providerRepo.create(prov1)
      _            <- providerRepo.create(prov2)
      item1         = Item.create(
                        id                   = ItemId.generate,
                        providerId           = prov1.id,
                        name                 = "Item A",
                        description          = "desc",
                        dailyRate            = price,
                        stock                = 5,
                        attendantRequirement = AttendantRequirement.Optional
                      ).toOption.get
      item2         = Item.create(
                        id                   = ItemId.generate,
                        providerId           = prov2.id,
                        name                 = "Item B",
                        description          = "desc",
                        dailyRate            = price,
                        stock                = 5,
                        attendantRequirement = AttendantRequirement.Optional
                      ).toOption.get
      _            <- itemRepo.create(item1)
      _            <- itemRepo.create(item2)
      _            <- svc.createBookingByProvider(prov1.id, CreateBookingByProviderRequest(List(BookingLineInput(item1.id, 1)), date, deliveryAddress, customerInput))
      _            <- svc.createBookingByProvider(prov2.id, CreateBookingByProviderRequest(List(BookingLineInput(item2.id, 1)), date, deliveryAddress, customerInput))
      list1        <- svc.listBookings(prov1.id, None, None, None)
      list2        <- svc.listBookings(prov2.id, None, None, None)
    yield
      assertEquals(list1.size, 1, "Provider 1 should see only its own booking")
      assertEquals(list2.size, 1, "Provider 2 should see only its own booking")
      assert(list1.forall(_.providerId == prov1.id))
      assert(list2.forall(_.providerId == prov2.id))
  }

  test("createBookingByProvider fails when provider does not exist") {
    for
      ctx    <- makeCtx()
      item   <- seedItem(ctx)
      bogus   = ProviderId.generate
      req     = CreateBookingByProviderRequest(
                  items           = List(BookingLineInput(item.id, 1)),
                  date            = date,
                  deliveryAddress = deliveryAddress,
                  customer        = customerInput
                )
      result <- ctx.svc.createBookingByProvider(bogus, req).attempt
    yield
      assert(result.isLeft, "Expected failure when provider does not exist")
      result.left.foreach {
        case _: BookingError.ProviderIdNotFound => ()
        case other                              => fail(s"Expected ProviderIdNotFound, got $other")
      }
  }

  // ── updateBookingStatus ────────────────────────────────────────────────────

  /** Context wired with AvailabilityServiceImpl so stock-release tests work. */
  private def makeCtxFull(logger: Logger[IO] = NoOpLogger[IO]): IO[Ctx] =
    given Logger[IO] = logger
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      customerRepo  <- InMemoryCustomerRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      itemRepo      <- InMemoryItemRepository.make[IO]
      comboRepo     <- InMemoryComboRepository.make[IO]
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      notifRepo     <- InMemoryNotificationEventRepository.make[IO]
      provider       = Provider.create(
                         id             = ProviderId.generate,
                         email          = Email.fromString("locador@example.com").toOption.get,
                         taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                         businessName   = "Locador LTDA",
                         tradeName      = "Locador",
                         city           = "São Paulo",
                         state          = "SP",
                         storefrontSlug = slug
                       ).toOption.get
      _             <- providerRepo.create(provider)
      availability   = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      svc            = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availability, attendantRepo, notifRepo)
    yield Ctx(svc, providerRepo, customerRepo, bookingRepo, itemRepo, attendantRepo, notifRepo, provider)

  private def createConfirmedBooking(ctx: Ctx, item: Item): IO[BookingId] =
    val req = CreateBookingByProviderRequest(
      items           = List(BookingLineInput(item.id, 1)),
      date            = date,
      deliveryAddress = deliveryAddress,
      customer        = customerInput
    )
    ctx.svc.createBookingByProvider(ctx.provider.id, req).map(_.bookingId)

  test("updateBookingStatus — valid transition returns updated Booking with new status") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      updated   <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.InProgress, None)
    yield
      assertEquals(updated.status, BookingStatus.InProgress)
      assertEquals(updated.id, bookingId)
  }

  test("updateBookingStatus — booking not found raises BookingNotFound") {
    for
      ctx    <- makeCtx()
      bogus   = BookingId.generate
      result <- ctx.svc.updateBookingStatus(ctx.provider.id, bogus, BookingStatus.Confirmed, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingError.BookingNotFound => ()
        case other                           => fail(s"Expected BookingNotFound, got $other")
      }
  }

  test("updateBookingStatus — booking belongs to different provider raises BookingNotFound") {
    for
      ctx         <- makeCtx()
      otherPid     = ProviderId.generate
      item        <- seedItem(ctx)
      bookingId   <- createConfirmedBooking(ctx, item)
      result      <- ctx.svc.updateBookingStatus(otherPid, bookingId, BookingStatus.InProgress, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingError.BookingNotFound => ()
        case other                           => fail(s"Expected BookingNotFound for cross-provider access, got $other")
      }
  }

  test("updateBookingStatus — invalid status transition raises InvalidInput") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      // Confirmed → Pending is not a valid transition
      result    <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Pending, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingError.InvalidInput => ()
        case other                        => fail(s"Expected InvalidInput, got $other")
      }
  }

  test("updateBookingStatus — cannot cancel InProgress booking raises InvalidInput") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.InProgress, None)
      result    <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Cancelled, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingError.InvalidInput => ()
        case other                        => fail(s"Expected InvalidInput when cancelling InProgress, got $other")
      }
  }

  test("updateBookingStatus — Confirmed→Cancelled releases stock") {
    for
      ctx       <- makeCtxFull()
      item      <- seedItem(ctx, stock = 1)
      bookingId <- createConfirmedBooking(ctx, item)
      // Second booking attempt must fail — stock consumed
      blocked   <- ctx.svc.createBookingByProvider(ctx.provider.id,
                     CreateBookingByProviderRequest(List(BookingLineInput(item.id, 1)), date, deliveryAddress, customerInput)
                   ).attempt
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Cancelled, Some("test cancel"))
      // After cancel, a new booking must succeed
      released  <- ctx.svc.createBookingByProvider(ctx.provider.id,
                     CreateBookingByProviderRequest(List(BookingLineInput(item.id, 1)), date, deliveryAddress, customerInput)
                   ).attempt
    yield
      assert(blocked.isLeft, "Second booking should be blocked while first is Confirmed")
      blocked.left.foreach {
        case _: BookingError.ItemsUnavailable => ()
        case other                            => fail(s"Expected ItemsUnavailable, got $other")
      }
      assert(released.isRight, "Booking should succeed after cancellation releases stock")
  }

  test("updateBookingStatus — emits BookingStatusChanged log on valid transition") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      ctx               <- makeCtx(logger = logger)
      item              <- seedItem(ctx)
      bookingId         <- createConfirmedBooking(ctx, item)
      _                 <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.InProgress, None)
      logs              <- getLogs
    yield
      val statusLog = logs.find(_.contains("\"event\":\"BookingStatusChanged\""))
      assert(statusLog.isDefined, s"Expected BookingStatusChanged log, got: $logs")
      assert(statusLog.get.contains(bookingId.value), statusLog.get)
      assert(statusLog.get.contains("\"fromStatus\":\"confirmed\""), statusLog.get)
      assert(statusLog.get.contains("\"toStatus\":\"in-progress\""), statusLog.get)
  }

  test("updateBookingStatus — emits reason in log when provided") {
    for
      capt              <- CapturingLogger.make
      (logger, getLogs)  = capt
      ctx               <- makeCtx(logger = logger)
      item              <- seedItem(ctx)
      bookingId         <- createConfirmedBooking(ctx, item)
      _                 <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Cancelled, Some("customer request"))
      logs              <- getLogs
    yield
      val statusLog = logs.find(_.contains("\"event\":\"BookingStatusChanged\""))
      assert(statusLog.isDefined, s"Expected BookingStatusChanged log, got: $logs")
      assert(statusLog.get.contains("\"reason\":\"customer request\""), statusLog.get)
  }

  test("updateBookingStatus — full lifecycle Pending→Confirmed→InProgress→Completed") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      // Start with a customer booking (Pending)
      created   <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid        = created.bookingId
      after1    <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None)
      after2    <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.InProgress, None)
      after3    <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Completed, None)
    yield
      assertEquals(after1.status, BookingStatus.Confirmed)
      assertEquals(after2.status, BookingStatus.InProgress)
      assertEquals(after3.status, BookingStatus.Completed)
  }

  // ── attendant confirmation guard ──────────────────────────────────────────

  private def buildRequiredItem(ctx: Ctx): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = ctx.provider.id,
      name                 = "Pula-Pula Gigante",
      description          = "Requer monitor",
      dailyRate            = price,
      stock                = 5,
      attendantRequirement = AttendantRequirement.Required
    ).toOption.get
    ctx.itemRepo.create(item).as(item)

  test("updateBookingStatus — Pending to Confirmed succeeds when Required-item booking has attendant assigned") {
    for
      ctx       <- makeCtx()
      item      <- buildRequiredItem(ctx)
      created   <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid        = created.bookingId
      attendant  = Attendant.create(AttendantId.generate, ctx.provider.id, "Monitor Joao", "11900000000").toOption.get
      _         <- ctx.attendantRepo.create(attendant)
      _         <- ctx.attendantRepo.assignToBooking(bid, attendant.id)
      updated   <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None)
    yield assertEquals(updated.status, BookingStatus.Confirmed)
  }

  test("updateBookingStatus — Pending to Confirmed raises InvalidInput when Required-item booking has no attendant") {
    for
      ctx     <- makeCtx()
      item    <- buildRequiredItem(ctx)
      created <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid      = created.bookingId
      result  <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingError.InvalidInput => ()
        case other                        => fail(s"Expected InvalidInput for missing required attendant, got $other")
      }
  }

  test("updateBookingStatus — Pending to Confirmed with Optional-item does not require attendant") {
    for
      ctx     <- makeCtx()
      item    <- seedItem(ctx)
      created <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid      = created.bookingId
      updated <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None)
    yield assertEquals(updated.status, BookingStatus.Confirmed)
  }

  test("integration: Required-item booking fails confirm without attendant, succeeds after assignment") {
    for
      ctx       <- makeCtx()
      item      <- buildRequiredItem(ctx)
      created   <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid        = created.bookingId
      failed    <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None).attempt
      attendant  = Attendant.create(AttendantId.generate, ctx.provider.id, "Monitor Maria", "11900000001").toOption.get
      _         <- ctx.attendantRepo.create(attendant)
      _         <- ctx.attendantRepo.assignToBooking(bid, attendant.id)
      updated   <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None)
    yield
      assert(failed.isLeft, "Expected confirmation to fail without attendant")
      assertEquals(updated.status, BookingStatus.Confirmed)
  }

  // ── BookingStatusChanged notification events ──────────────────────────────

  test("updateBookingStatus — Pending→Confirmed enqueues a BookingStatusChanged notification event") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      created   <- ctx.svc.createBooking(request(List((item.id, 1))))
      bid        = created.bookingId
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bid, BookingStatus.Confirmed, None)
      events    <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 1, "Expected exactly one notification event")
      assertEquals(events.head.eventType, "BookingStatusChanged")
      assert(events.head.payload.contains(bid.value), s"Payload should contain bookingId: ${events.head.payload}")
      assert(events.head.payload.contains("\"previousStatus\":\"pending\""), events.head.payload)
      assert(events.head.payload.contains("\"newStatus\":\"confirmed\""), events.head.payload)
  }

  test("updateBookingStatus — Confirmed→Cancelled enqueues a BookingStatusChanged notification event") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Cancelled, None)
      events    <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 1, "Expected exactly one notification event")
      assertEquals(events.head.eventType, "BookingStatusChanged")
      assert(events.head.payload.contains("\"previousStatus\":\"confirmed\""), events.head.payload)
      assert(events.head.payload.contains("\"newStatus\":\"cancelled\""), events.head.payload)
  }

  test("updateBookingStatus — Confirmed→InProgress does NOT enqueue a notification event") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.InProgress, None)
      events    <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 0, "Expected no notification events for InProgress transition")
  }

  test("updateBookingStatus — InProgress→Completed does NOT enqueue a notification event") {
    for
      ctx       <- makeCtx()
      item      <- seedItem(ctx)
      bookingId <- createConfirmedBooking(ctx, item)
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.InProgress, None)
      _         <- ctx.svc.updateBookingStatus(ctx.provider.id, bookingId, BookingStatus.Completed, None)
      events    <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 0, "Expected no notification events for Completed transition")
  }
