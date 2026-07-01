package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.AsaasWebhookService
import com.locarya.helpers.{
  InMemoryBookingChargeRepository,
  InMemoryNotificationEventRepository,
  InMemoryPaymentRepository
}
import munit.CatsEffectSuite
import java.time.{Instant, LocalDate}

class AsaasWebhookServiceSpec extends CatsEffectSuite:

  private val asaasChargeId = "pay_test_abc123"
  private val amount        = BigDecimal("500.00")
  private val bookingId     = BookingId.generate

  private val pendingCharge = BookingCharge.fromDb(
    id         = BookingChargeId.generate,
    bookingId  = bookingId,
    chargeId   = asaasChargeId,
    paymentUrl = "https://asaas.com/pay/abc123",
    status     = BookingChargeStatus.Pending,
    createdAt  = Instant.now()
  )

  private val paidCharge = BookingCharge.fromDb(
    id         = BookingChargeId.generate,
    bookingId  = BookingId.generate,
    chargeId   = "pay_already_paid",
    paymentUrl = "https://asaas.com/pay/paid",
    status     = BookingChargeStatus.Paid,
    createdAt  = Instant.now()
  )

  private case class Ctx(
    svc:         AsaasWebhookService[IO],
    chargeRepo:  InMemoryBookingChargeRepository[IO],
    paymentRepo: InMemoryPaymentRepository[IO],
    notifRepo:   InMemoryNotificationEventRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      chargeRepo  <- InMemoryBookingChargeRepository.make[IO]
      paymentRepo <- InMemoryPaymentRepository.make[IO]
      notifRepo   <- InMemoryNotificationEventRepository.make[IO]
      svc          = AsaasWebhookServiceImpl[IO](chargeRepo, paymentRepo, notifRepo)
    yield Ctx(svc, chargeRepo, paymentRepo, notifRepo)

  // ── PAYMENT_CONFIRMED: happy path ─────────────────────────────────────────

  test("handlePaymentConfirmed creates Payment with PixAsaas method for pending charge") {
    for
      ctx <- makeCtx
      _   <- ctx.chargeRepo.create(pendingCharge)
      _   <- ctx.svc.handlePaymentConfirmed(asaasChargeId, amount)
      payments <- ctx.paymentRepo.findByBooking(bookingId)
    yield
      assertEquals(payments.size, 1)
      assertEquals(payments.head.method, PaymentMethod.PixAsaas)
      assertEquals(payments.head.amount.amount, amount)
      assertEquals(payments.head.bookingId, bookingId)
  }

  test("handlePaymentConfirmed marks BookingCharge as Paid") {
    for
      ctx     <- makeCtx
      _       <- ctx.chargeRepo.create(pendingCharge)
      _       <- ctx.svc.handlePaymentConfirmed(asaasChargeId, amount)
      updated <- ctx.chargeRepo.findByAsaasChargeId(asaasChargeId)
    yield
      assert(updated.isDefined, "Charge should exist after update")
      assertEquals(updated.get.status, BookingChargeStatus.Paid)
  }

  test("handlePaymentConfirmed enqueues a PaymentConfirmed notification event") {
    for
      ctx    <- makeCtx
      _      <- ctx.chargeRepo.create(pendingCharge)
      _      <- ctx.svc.handlePaymentConfirmed(asaasChargeId, amount)
      events <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 1)
      assertEquals(events.head.eventType, "PaymentConfirmed")
      assertEquals(events.head.status, NotificationEventStatus.Pending)
      assert(events.head.payload.contains(asaasChargeId), "Payload should contain the asaas charge id")
  }

  // ── PAYMENT_CONFIRMED: idempotency ────────────────────────────────────────

  test("duplicate event for already-paid charge is silently ignored (no Payment created)") {
    for
      ctx      <- makeCtx
      _        <- ctx.chargeRepo.create(paidCharge)
      _        <- ctx.svc.handlePaymentConfirmed("pay_already_paid", amount)
      payments <- ctx.paymentRepo.findByBooking(paidCharge.bookingId)
    yield
      assertEquals(payments.size, 0, "No Payment should be created for an already-paid charge")
  }

  test("duplicate event for already-paid charge does not enqueue a notification") {
    for
      ctx    <- makeCtx
      _      <- ctx.chargeRepo.create(paidCharge)
      _      <- ctx.svc.handlePaymentConfirmed("pay_already_paid", amount)
      events <- ctx.notifRepo.all
    yield
      assertEquals(events.size, 0, "No notification should be created for an already-paid charge")
  }

  // ── PAYMENT_CONFIRMED: unknown charge ─────────────────────────────────────

  test("handlePaymentConfirmed with unknown asaasChargeId is silently ignored") {
    for
      ctx      <- makeCtx
      _        <- ctx.svc.handlePaymentConfirmed("pay_unknown_xyz", amount)
      payments <- ctx.paymentRepo.findByBooking(bookingId)
      events   <- ctx.notifRepo.all
    yield
      assertEquals(payments.size, 0)
      assertEquals(events.size, 0)
  }
