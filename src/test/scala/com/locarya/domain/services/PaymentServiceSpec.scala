package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{
  CapturingLogger,
  InMemoryBookingRepository,
  InMemoryPaymentRepository,
  InMemoryProviderRepository
}
import java.time.LocalDate
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class PaymentServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val date      = LocalDate.of(2026, 9, 1)
  private val totalMoney = Money.fromAmount(BigDecimal("500.00")).toOption.get

  private val deliveryAddress =
    Address.create("Rua A", "100", "Centro", "São Paulo", "SP", "01000-000", None).toOption.get

  private case class Ctx(
    svc:          PaymentService[IO],
    paymentRepo:  InMemoryPaymentRepository[IO],
    bookingRepo:  InMemoryBookingRepository[IO],
    providerRepo: InMemoryProviderRepository[IO],
    provider:     Provider,
    booking:      Booking
  )

  private def makeCtx(logger: Logger[IO] = NoOpLogger[IO]): IO[Ctx] =
    given Logger[IO] = logger
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      paymentRepo  <- InMemoryPaymentRepository.make[IO]
      provider      = Provider.create(
                        id             = ProviderId.generate,
                        email          = Email.fromString("locador@example.com").toOption.get,
                        taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
                        businessName   = "Locador LTDA",
                        tradeName      = "Locador",
                        city           = "São Paulo",
                        state          = "SP",
                        storefrontSlug = StorefrontSlug.fromString("loja-test").toOption.get
                      ).toOption.get
      _            <- providerRepo.create(provider)
      booking       = Booking.create(
                        id          = BookingId.generate,
                        providerId  = provider.id,
                        customerId  = CustomerId.generate,
                        items       = List(BookedIndividualItem(ItemId.generate, 1)),
                        startDate   = date,
                        endDate     = date,
                        totalAmount = totalMoney,
                        createdAt   = java.time.Instant.EPOCH,
                        status      = BookingStatus.Confirmed
                      ).toOption.get
      _            <- bookingRepo.create(booking)
      svc           = PaymentServiceImpl[IO](bookingRepo, paymentRepo)
    yield Ctx(svc, paymentRepo, bookingRepo, providerRepo, provider, booking)

  // ── recordPayment ─────────────────────────────────────────────────────────

  test("recordPayment creates payment with status Confirmed") {
    for
      ctx     <- makeCtx()
      payment <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("200.00"), PaymentMethod.PixManual, None)
    yield
      assertEquals(payment.status, PaymentStatus.Confirmed)
      assertEquals(payment.bookingId, ctx.booking.id)
      assertEquals(payment.amount.amount, BigDecimal("200.00"))
      assertEquals(payment.method, PaymentMethod.PixManual)
  }

  test("recordPayment persists payment linkable via findByBooking") {
    for
      ctx     <- makeCtx()
      payment <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("100.00"), PaymentMethod.PixManual, None)
      found   <- ctx.paymentRepo.findByBooking(ctx.booking.id)
    yield
      assertEquals(found.size, 1)
      assertEquals(found.head.id, payment.id)
  }

  test("recordPayment stores optional note") {
    for
      ctx     <- makeCtx()
      payment <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("100.00"), PaymentMethod.PixManual, Some("sinal"))
    yield assertEquals(payment.note, Some("sinal"))
  }

  // ── validation ────────────────────────────────────────────────────────────

  test("recordPayment raises InvalidInput for amount <= 0") {
    for
      ctx    <- makeCtx()
      result <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("0"), PaymentMethod.PixManual, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.InvalidInput => ()
        case other                        => fail(s"Expected InvalidInput, got $other")
      }
  }

  test("recordPayment raises BookingNotFound for unknown bookingId") {
    for
      ctx    <- makeCtx()
      bogus   = BookingId.generate
      result <- ctx.svc.recordPayment(ctx.provider.id, bogus, BigDecimal("100.00"), PaymentMethod.PixManual, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.BookingNotFound => ()
        case other                           => fail(s"Expected BookingNotFound, got $other")
      }
  }

  test("recordPayment raises Forbidden when booking belongs to different provider") {
    for
      ctx       <- makeCtx()
      otherId    = ProviderId.generate
      result    <- ctx.svc.recordPayment(otherId, ctx.booking.id, BigDecimal("100.00"), PaymentMethod.PixManual, None).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.Forbidden => ()
        case other                     => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── logging ───────────────────────────────────────────────────────────────

  test("recordPayment emits a single PaymentRecorded log line with required fields") {
    for
      capt             <- CapturingLogger.make
      (logger, getLogs) = capt
      ctx              <- makeCtx(logger)
      payment          <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("150.00"), PaymentMethod.PixManual, None)
      logs             <- getLogs
    yield
      assertEquals(logs.size, 1)
      assert(logs.head.contains("\"event\":\"PaymentRecorded\""),  logs.head)
      assert(logs.head.contains(ctx.booking.id.value),             logs.head)
      assert(logs.head.contains(payment.id.value),                 logs.head)
      assert(logs.head.contains("150"),                            logs.head)
      assert(logs.head.contains("PIX_MANUAL"),                     logs.head)
  }

  // ── getPaymentSummary ─────────────────────────────────────────────────────

  test("getPaymentSummary returns total, paid, and balanceDue") {
    for
      ctx     <- makeCtx()
      _       <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("200.00"), PaymentMethod.PixManual, None)
      summary <- ctx.svc.getPaymentSummary(ctx.provider.id, ctx.booking.id)
    yield
      assertEquals(summary.total,      BigDecimal("500.00"))
      assertEquals(summary.paid,       BigDecimal("200.00"))
      assertEquals(summary.balanceDue, BigDecimal("300.00"))
  }

  test("getPaymentSummary with no payments shows full balance due") {
    for
      ctx     <- makeCtx()
      summary <- ctx.svc.getPaymentSummary(ctx.provider.id, ctx.booking.id)
    yield
      assertEquals(summary.total,      BigDecimal("500.00"))
      assertEquals(summary.paid,       BigDecimal("0"))
      assertEquals(summary.balanceDue, BigDecimal("500.00"))
  }

  test("getPaymentSummary raises BookingNotFound for unknown booking") {
    for
      ctx    <- makeCtx()
      result <- ctx.svc.getPaymentSummary(ctx.provider.id, BookingId.generate).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.BookingNotFound => ()
        case other                           => fail(s"Expected BookingNotFound, got $other")
      }
  }

  test("getPaymentSummary raises Forbidden for another provider's booking") {
    for
      ctx    <- makeCtx()
      result <- ctx.svc.getPaymentSummary(ProviderId.generate, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.Forbidden => ()
        case other                     => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── listPayments ──────────────────────────────────────────────────────────

  test("listPayments returns all payments for a booking") {
    for
      ctx  <- makeCtx()
      _    <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("100.00"), PaymentMethod.PixManual, None)
      _    <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("50.00"),  PaymentMethod.PixManual, None)
      list <- ctx.svc.listPayments(ctx.provider.id, ctx.booking.id)
    yield
      assertEquals(list.size, 2)
      assert(list.map(_.amount.amount).toSet == Set(BigDecimal("100.00"), BigDecimal("50.00")))
  }

  test("listPayments raises Forbidden for another provider's booking") {
    for
      ctx    <- makeCtx()
      result <- ctx.svc.listPayments(ProviderId.generate, ctx.booking.id).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: PaymentError.Forbidden => ()
        case other                     => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── integration ───────────────────────────────────────────────────────────

  test("integration: create booking, record payment, findByBooking returns it") {
    for
      ctx     <- makeCtx()
      payment <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("300.00"), PaymentMethod.PixManual, Some("entrada"))
      found   <- ctx.paymentRepo.findByBooking(ctx.booking.id)
    yield
      assertEquals(found.size, 1)
      assertEquals(found.head.id, payment.id)
      assertEquals(found.head.amount.amount, BigDecimal("300.00"))
      assertEquals(found.head.note, Some("entrada"))
  }

  test("integration: multiple payments sum equals booking total") {
    for
      ctx  <- makeCtx()
      _    <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("200.00"), PaymentMethod.PixManual, None)
      _    <- ctx.svc.recordPayment(ctx.provider.id, ctx.booking.id, BigDecimal("300.00"), PaymentMethod.PixManual, None)
      list <- ctx.paymentRepo.findByBooking(ctx.booking.id)
    yield
      val sum = list.map(_.amount.amount).sum
      assertEquals(sum, ctx.booking.totalAmount.amount)
  }
