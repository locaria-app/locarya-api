package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{
  InMemoryBookingChargeRepository,
  InMemoryBookingRepository,
  InMemoryCustomerRepository,
  InMemoryProviderRepository,
  PaymentGatewayStub
}
import java.time.LocalDate
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class BookingChargeServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val slug       = StorefrontSlug.fromString("loja-test-123456").toOption.get
  private val date       = LocalDate.of(2026, 9, 1)
  private val totalMoney = Money.fromAmount(BigDecimal("500.00")).toOption.get

  private val premiumProvider = Provider.create(
    id             = ProviderId.generate,
    email          = Email.fromString("locador@test.com").toOption.get,
    taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
    businessName   = "Locador LTDA",
    tradeName      = "Locador",
    city           = "São Paulo",
    state          = "SP",
    planTier       = PlanTier.Premium,
    storefrontSlug = slug,
    walletId       = Some("wlt_abc123")
  ).toOption.get

  private val freemiumProvider = Provider.create(
    id             = ProviderId.generate,
    email          = Email.fromString("freemium@test.com").toOption.get,
    taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
    businessName   = "Freemium LTDA",
    tradeName      = "Freemium",
    city           = "São Paulo",
    state          = "SP",
    planTier       = PlanTier.Freemium,
    storefrontSlug = StorefrontSlug.fromString("freemium-slug-999999").toOption.get,
    walletId       = None
  ).toOption.get

  private case class Ctx(
    svc:         BookingChargeService[IO],
    chargeRepo:  InMemoryBookingChargeRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO],
    gateway:     PaymentGatewayStub[IO],
    booking:     Booking,
    provider:    Provider
  )

  private def makeCtx(provider: Provider = premiumProvider): IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      customerRepo <- InMemoryCustomerRepository.make[IO]
      chargeRepo   <- InMemoryBookingChargeRepository.make[IO]
      gateway      <- PaymentGatewayStub.make[IO]
      _            <- providerRepo.create(provider)
      customer      = Customer.create(
                        id    = CustomerId.generate,
                        email = Email.fromString("cliente@test.com").toOption.get,
                        name  = "Cliente Teste",
                        phone = None
                      ).toOption.get
      _            <- customerRepo.create(customer)
      booking       = Booking.create(
                        id          = BookingId.generate,
                        providerId  = provider.id,
                        customerId  = customer.id,
                        items       = List(BookedIndividualItem(ItemId.generate, 1)),
                        startDate   = date,
                        endDate     = date,
                        totalAmount = totalMoney,
                        status      = BookingStatus.Pending
                      ).toOption.get
      _            <- bookingRepo.create(booking)
      svc           = BookingChargeServiceImpl[IO](providerRepo, bookingRepo, customerRepo, chargeRepo, gateway)
    yield Ctx(svc, chargeRepo, bookingRepo, gateway, booking, provider)

  // ── chargeBooking: happy path ─────────────────────────────────────────────

  test("chargeBooking creates charge and returns ChargeOutcome.Created for Premium provider with walletId") {
    for
      ctx    <- makeCtx()
      outcome <- ctx.svc.chargeBooking(slug, ctx.booking.id)
    yield
      assert(outcome.isInstanceOf[ChargeOutcome.Created], s"Expected Created, got $outcome")
      assert(outcome.paymentUrl.nonEmpty)
  }

  test("chargeBooking persists BookingCharge with Pending status") {
    for
      ctx   <- makeCtx()
      _     <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      found <- ctx.chargeRepo.findPendingByBooking(ctx.booking.id)
    yield
      assert(found.isDefined, "Expected a pending charge to be persisted")
      assertEquals(found.get.status, BookingChargeStatus.Pending)
      assertEquals(found.get.bookingId, ctx.booking.id)
  }

  test("chargeBooking calls PaymentGateway.createCharge exactly once") {
    for
      ctx   <- makeCtx()
      _     <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      count <- ctx.gateway.createChargeCallCount
    yield assertEquals(count, 1)
  }

  // ── chargeBooking: idempotency ───────────────────────────────────────────

  test("second chargeBooking returns ChargeOutcome.ExistingPending with same paymentUrl") {
    for
      ctx      <- makeCtx()
      first    <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      second   <- ctx.svc.chargeBooking(slug, ctx.booking.id)
    yield
      assert(second.isInstanceOf[ChargeOutcome.ExistingPending], s"Expected ExistingPending, got $second")
      assertEquals(second.paymentUrl, first.paymentUrl)
  }

  test("second chargeBooking does NOT call PaymentGateway again (idempotent)") {
    for
      ctx   <- makeCtx()
      _     <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      _     <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      count <- ctx.gateway.createChargeCallCount
    yield assertEquals(count, 1)
  }

  test("BookingStatus is not changed after chargeBooking") {
    for
      ctx     <- makeCtx()
      _       <- ctx.svc.chargeBooking(slug, ctx.booking.id)
      stored  <- ctx.bookingRepo.findById(ctx.booking.id)
    yield assertEquals(stored.get.status, BookingStatus.Pending)
  }

  // ── chargeBooking: 403 cases ──────────────────────────────────────────────

  test("chargeBooking raises OnlinePaymentNotEnabled for Freemium provider") {
    val freemiumSlug = StorefrontSlug.fromString("freemium-slug-999999").toOption.get
    for
      ctx    <- makeCtx(freemiumProvider)
      result <- ctx.svc.chargeBooking(freemiumSlug, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingChargeError.OnlinePaymentNotEnabled => ()
        case other => fail(s"Expected OnlinePaymentNotEnabled, got $other")
      }
  }

  test("chargeBooking raises OnlinePaymentNotEnabled for Premium provider without walletId") {
    val noWalletSlug = StorefrontSlug.fromString("nowallet-slug-111111").toOption.get
    val noWalletProvider = Provider.create(
      id             = ProviderId.generate,
      email          = Email.fromString("nowallet@test.com").toOption.get,
      taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
      businessName   = "No Wallet LTDA",
      tradeName      = "No Wallet",
      city           = "São Paulo",
      state          = "SP",
      planTier       = PlanTier.Premium,
      storefrontSlug = noWalletSlug,
      walletId       = None
    ).toOption.get
    for
      ctx    <- makeCtx(noWalletProvider)
      result <- ctx.svc.chargeBooking(noWalletSlug, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingChargeError.OnlinePaymentNotEnabled => ()
        case other => fail(s"Expected OnlinePaymentNotEnabled, got $other")
      }
  }

  // ── chargeBooking: 404 cases ──────────────────────────────────────────────

  test("chargeBooking raises NotFound for unknown slug") {
    for
      ctx    <- makeCtx()
      bogus   = StorefrontSlug.fromString("no-such-slug-000000").toOption.get
      result <- ctx.svc.chargeBooking(bogus, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingChargeError.NotFound => ()
        case other => fail(s"Expected NotFound, got $other")
      }
  }

  test("chargeBooking raises NotFound for unknown bookingId") {
    for
      ctx    <- makeCtx()
      bogus   = BookingId.generate
      result <- ctx.svc.chargeBooking(slug, bogus).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingChargeError.NotFound => ()
        case other => fail(s"Expected NotFound, got $other")
      }
  }

  test("chargeBooking raises NotFound when booking belongs to a different provider") {
    val otherSlug = StorefrontSlug.fromString("other-provider-222222").toOption.get
    val otherProvider = Provider.create(
      id             = ProviderId.generate,
      email          = Email.fromString("other@test.com").toOption.get,
      taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
      businessName   = "Other LTDA",
      tradeName      = "Other",
      city           = "São Paulo",
      state          = "SP",
      planTier       = PlanTier.Premium,
      storefrontSlug = otherSlug,
      walletId       = Some("wlt_other")
    ).toOption.get
    for
      ctx         <- makeCtx()
      providerRepo <- InMemoryProviderRepository.make[IO]
      _           <- providerRepo.create(otherProvider)
      bookingRepo <- InMemoryBookingRepository.make[IO]
      customerRepo <- InMemoryCustomerRepository.make[IO]
      chargeRepo  <- InMemoryBookingChargeRepository.make[IO]
      gateway     <- PaymentGatewayStub.make[IO]
      // booking was created under premiumProvider, not otherProvider
      _           <- bookingRepo.create(ctx.booking)
      svc          = BookingChargeServiceImpl[IO](providerRepo, bookingRepo, customerRepo, chargeRepo, gateway)
      result      <- svc.chargeBooking(otherSlug, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: BookingChargeError.NotFound => ()
        case other => fail(s"Expected NotFound, got $other")
      }
  }
