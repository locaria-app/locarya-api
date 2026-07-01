package com.locarya.domain.services

import cats.effect.IO
import cats.effect.Sync
import com.locarya.domain.models.*
import com.locarya.domain.ports.NotificationService
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryCustomerRepository,
  InMemoryNotificationEventRepository,
  InMemoryPaymentRepository,
  InMemoryProviderRepository,
  NotificationServiceStub
}
import java.time.{Instant, LocalDate}
import munit.CatsEffectSuite

class NotificationOutboxWorkerSpec extends CatsEffectSuite:

  private val providerId = ProviderId.generate
  private val customerId = CustomerId.generate
  private val bookingId  = BookingId.generate
  private val paymentId  = PaymentId.generate
  private val date       = LocalDate.of(2026, 9, 1)

  private val provider = Provider.create(
    id           = providerId,
    email        = Email.fromString("locador@test.com").toOption.get,
    taxId        = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
    businessName = "Locador LTDA",
    tradeName    = "Locador",
    city         = "São Paulo",
    state        = "SP"
  ).toOption.get

  private val customer = Customer.create(
    id    = customerId,
    email = Email.fromString("cliente@test.com").toOption.get,
    name  = "Cliente Teste"
  ).toOption.get

  private val booking = Booking.create(
    id          = bookingId,
    providerId  = providerId,
    customerId  = customerId,
    items       = List(BookedIndividualItem(ItemId.generate, 1)),
    startDate   = date,
    endDate     = date,
    totalAmount = Money.fromAmount(BigDecimal("500.00")).toOption.get,
    status      = BookingStatus.Pending
  ).toOption.get

  private val payment = Payment.create(
    id        = paymentId,
    bookingId = bookingId,
    amount    = BigDecimal("500.00"),
    method    = PaymentMethod.PixAsaas,
    note      = None,
    paidAt    = Instant.now()
  ).toOption.get

  private val paymentConfirmedPayload =
    s"""{"asaasChargeId":"charge_abc","bookingId":"${bookingId.value}","paymentId":"${paymentId.value}"}"""

  private val bookingLinkPayload =
    s"""{"bookingId":"${bookingId.value}","paymentUrl":"https://asaas.com/pay/abc"}"""

  private case class Ctx(
    notifRepo:   InMemoryNotificationEventRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO],
    customerRepo: InMemoryCustomerRepository[IO],
    providerRepo: InMemoryProviderRepository[IO],
    paymentRepo: InMemoryPaymentRepository[IO],
    notifSvc:    NotificationServiceStub[IO]
  ):
    def worker(svc: NotificationService[IO] = notifSvc): NotificationOutboxWorker[IO] =
      NotificationOutboxWorker[IO](notifRepo, bookingRepo, customerRepo, providerRepo, paymentRepo, svc)

  private def makeCtx: IO[Ctx] =
    for
      notifRepo    <- InMemoryNotificationEventRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      customerRepo <- InMemoryCustomerRepository.make[IO]
      providerRepo <- InMemoryProviderRepository.make[IO]
      paymentRepo  <- InMemoryPaymentRepository.make[IO]
      notifSvc     <- NotificationServiceStub.make[IO]
      _            <- bookingRepo.create(booking)
      _            <- customerRepo.create(customer)
      _            <- providerRepo.create(provider)
      _            <- paymentRepo.create(payment)
    yield Ctx(notifRepo, bookingRepo, customerRepo, providerRepo, paymentRepo, notifSvc)

  private def pendingEvent(eventType: String, payload: String): NotificationEvent =
    NotificationEvent.create(NotificationEventId.generate, eventType, payload, Instant.now())

  // ── PaymentConfirmed: happy path ──────────────────────────────────────────

  test("processOnce calls notify with PaymentConfirmed payload") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker().processOnce
      calls <- ctx.notifSvc.captured
    yield
      assertEquals(calls.size, 1)
      assert(calls.head.isInstanceOf[NotificationPayload.PaymentConfirmed], s"Expected PaymentConfirmed, got ${calls.head}")
      val pc = calls.head.asInstanceOf[NotificationPayload.PaymentConfirmed]
      assertEquals(pc.booking.id, bookingId)
      assertEquals(pc.customer.id, customerId)
      assertEquals(pc.provider.id, providerId)
      assertEquals(pc.amount.amount, BigDecimal("500.00"))
  }

  test("processOnce marks PaymentConfirmed event as Processed on success") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker().processOnce
      found <- ctx.notifRepo.findById(event.id)
    yield
      assert(found.isDefined)
      assertEquals(found.get.status, NotificationEventStatus.Processed)
  }

  // ── BookingCreatedWithPaymentLink: happy path ─────────────────────────────

  test("processOnce calls notify with BookingCreatedWithPaymentLink payload") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("BookingCreatedWithPaymentLink", bookingLinkPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker().processOnce
      calls <- ctx.notifSvc.captured
    yield
      assertEquals(calls.size, 1)
      assert(calls.head.isInstanceOf[NotificationPayload.BookingCreatedWithPaymentLink])
      val bc = calls.head.asInstanceOf[NotificationPayload.BookingCreatedWithPaymentLink]
      assertEquals(bc.booking.id, bookingId)
      assertEquals(bc.paymentUrl, "https://asaas.com/pay/abc")
  }

  test("processOnce marks BookingCreatedWithPaymentLink event as Processed on success") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("BookingCreatedWithPaymentLink", bookingLinkPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker().processOnce
      found <- ctx.notifRepo.findById(event.id)
    yield
      assert(found.isDefined)
      assertEquals(found.get.status, NotificationEventStatus.Processed)
  }

  // ── Retry logic ───────────────────────────────────────────────────────────

  test("processOnce increments retry_count to 1 when notify raises an error") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker(alwaysFail).processOnce
      found <- ctx.notifRepo.findById(event.id)
    yield
      assert(found.isDefined)
      assertEquals(found.get.retryCount, 1)
      assertEquals(found.get.status, NotificationEventStatus.Pending)
  }

  test("processOnce marks event as Failed after 3 consecutive failures") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker(alwaysFail).processOnce // attempt 1 → retryCount=1, Pending
      _     <- ctx.worker(alwaysFail).processOnce // attempt 2 → retryCount=2, Pending
      _     <- ctx.worker(alwaysFail).processOnce // attempt 3 → retryCount=3, Failed
      found <- ctx.notifRepo.findById(event.id)
    yield
      assert(found.isDefined)
      assertEquals(found.get.retryCount, 3)
      assertEquals(found.get.status, NotificationEventStatus.Failed)
  }

  test("processOnce does not re-process a Processed event") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker().processOnce
      _     <- ctx.worker().processOnce // second run — event is Processed, skipped
      calls <- ctx.notifSvc.captured
    yield
      assertEquals(calls.size, 1, "notify should only be called once")
  }

  test("processOnce skips Failed events") {
    for
      ctx   <- makeCtx
      event  = pendingEvent("PaymentConfirmed", paymentConfirmedPayload)
      _     <- ctx.notifRepo.create(event)
      _     <- ctx.worker(alwaysFail).processOnce
      _     <- ctx.worker(alwaysFail).processOnce
      _     <- ctx.worker(alwaysFail).processOnce // marks Failed
      _     <- ctx.worker().processOnce           // real stub — should be skipped
      calls <- ctx.notifSvc.captured
    yield
      assertEquals(calls.size, 0, "notify should not be called for a Failed event")
  }

  private def alwaysFail: NotificationService[IO] = new NotificationService[IO]:
    def notify(payload: NotificationPayload): IO[Unit] =
      IO.raiseError(new RuntimeException("notify failed"))
