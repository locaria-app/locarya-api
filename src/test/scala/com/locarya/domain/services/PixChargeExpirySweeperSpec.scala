package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.{
  InMemoryBookingChargeRepository,
  InMemoryBookingRepository,
  PaymentGatewayStub
}
import java.time.Instant
import java.time.LocalDate
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class PixChargeExpirySweeperSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val date = LocalDate.of(2026, 9, 1)
  private val money = Money.fromAmount(BigDecimal("100.00")).toOption.get

  private case class Ctx(
    svc:        PixChargeExpirySweeper[IO],
    chargeRepo: InMemoryBookingChargeRepository[IO],
    gateway:    PaymentGatewayStub[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      chargeRepo <- InMemoryBookingChargeRepository.make[IO]
      gateway    <- PaymentGatewayStub.make[IO]
      svc         = PixChargeExpirySweeper[IO](chargeRepo, gateway)
    yield Ctx(svc, chargeRepo, gateway)

  private def pendingCharge(createdAt: Instant): BookingCharge =
    val id = BookingChargeId.generate
    BookingCharge.fromDb(
      id         = id,
      bookingId  = BookingId.generate,
      chargeId   = s"charge_${id.value.take(8)}",
      paymentUrl = "https://asaas.com/pay/test",
      status     = BookingChargeStatus.Pending,
      createdAt  = createdAt
    )

  // ── processOnce: happy path ──────────────────────────────────────────────

  test("processOnce cancels and marks expired a pending charge older than 48h") {
    val charge = pendingCharge(Instant.EPOCH)
    for
      ctx       <- makeCtx
      _         <- ctx.chargeRepo.create(charge)
      _         <- ctx.svc.processOnce
      updated   <- ctx.chargeRepo.findById(charge.id)
      cancelled <- ctx.gateway.cancelledChargeIds
    yield
      assertEquals(updated.get.status, BookingChargeStatus.Expired)
      assert(cancelled.contains(charge.chargeId), s"Expected ${charge.chargeId} in $cancelled")
  }

  test("processOnce calls cancelCharge for each pending charge older than 48h") {
    val charge1 = pendingCharge(Instant.EPOCH)
    val charge2 = pendingCharge(Instant.EPOCH)
    for
      ctx       <- makeCtx
      _         <- ctx.chargeRepo.create(charge1)
      _         <- ctx.chargeRepo.create(charge2)
      _         <- ctx.svc.processOnce
      cancelled <- ctx.gateway.cancelledChargeIds
    yield
      assertEquals(cancelled.size, 2)
      assert(cancelled.contains(charge1.chargeId))
      assert(cancelled.contains(charge2.chargeId))
  }

  // ── processOnce: charges not touched ─────────────────────────────────────

  test("processOnce does not touch pending charges newer than 48h") {
    val charge = pendingCharge(Instant.now().plusSeconds(3600))
    for
      ctx   <- makeCtx
      _     <- ctx.chargeRepo.create(charge)
      _     <- ctx.svc.processOnce
      count <- ctx.gateway.cancelChargeCallCount
      found <- ctx.chargeRepo.findById(charge.id)
    yield
      assertEquals(count, 0)
      assertEquals(found.get.status, BookingChargeStatus.Pending)
  }

  test("processOnce does not reprocess paid charges older than 48h") {
    val charge = BookingCharge.fromDb(
      id         = BookingChargeId.generate,
      bookingId  = BookingId.generate,
      chargeId   = "charge_paid_old",
      paymentUrl = "https://asaas.com/pay/paid",
      status     = BookingChargeStatus.Paid,
      createdAt  = Instant.EPOCH
    )
    for
      ctx   <- makeCtx
      _     <- ctx.chargeRepo.create(charge)
      _     <- ctx.svc.processOnce
      count <- ctx.gateway.cancelChargeCallCount
      found <- ctx.chargeRepo.findById(charge.id)
    yield
      assertEquals(count, 0)
      assertEquals(found.get.status, BookingChargeStatus.Paid)
  }

  test("processOnce does not reprocess already-expired charges older than 48h") {
    val charge = BookingCharge.fromDb(
      id         = BookingChargeId.generate,
      bookingId  = BookingId.generate,
      chargeId   = "charge_expired_old",
      paymentUrl = "https://asaas.com/pay/expired",
      status     = BookingChargeStatus.Expired,
      createdAt  = Instant.EPOCH
    )
    for
      ctx   <- makeCtx
      _     <- ctx.chargeRepo.create(charge)
      _     <- ctx.svc.processOnce
      count <- ctx.gateway.cancelChargeCallCount
      found <- ctx.chargeRepo.findById(charge.id)
    yield
      assertEquals(count, 0)
      assertEquals(found.get.status, BookingChargeStatus.Expired)
  }

  // ── processOnce: BookingStatus untouched ─────────────────────────────────

  test("processOnce does not alter BookingStatus of the associated booking") {
    val booking = Booking.create(
      id          = BookingId.generate,
      providerId  = ProviderId.generate,
      customerId  = CustomerId.generate,
      items       = List(BookedIndividualItem(ItemId.generate, 1)),
      startDate   = date,
      endDate     = date,
      totalAmount = money
    ).toOption.get
    val chargeForBooking = BookingCharge.fromDb(
      id         = BookingChargeId.generate,
      bookingId  = booking.id,
      chargeId   = "charge_booking_status_test",
      paymentUrl = "https://asaas.com/booking-test",
      status     = BookingChargeStatus.Pending,
      createdAt  = Instant.EPOCH
    )
    for
      ctx         <- makeCtx
      bookingRepo <- InMemoryBookingRepository.make[IO]
      _           <- bookingRepo.create(booking)
      _           <- ctx.chargeRepo.create(chargeForBooking)
      _           <- ctx.svc.processOnce
      stored      <- bookingRepo.findById(booking.id)
    yield assertEquals(stored.get.status, BookingStatus.Pending)
  }

  // ── processOnce: error isolation ─────────────────────────────────────────

  test("processOnce continues processing remaining charges after one fails") {
    val charge1 = pendingCharge(Instant.EPOCH)
    val charge2 = pendingCharge(Instant.EPOCH)
    for
      ctx       <- makeCtx
      _         <- ctx.gateway.failFor(charge1.chargeId)
      _         <- ctx.chargeRepo.create(charge1)
      _         <- ctx.chargeRepo.create(charge2)
      _         <- ctx.svc.processOnce
      found1    <- ctx.chargeRepo.findById(charge1.id)
      found2    <- ctx.chargeRepo.findById(charge2.id)
      cancelled <- ctx.gateway.cancelledChargeIds
    yield
      assertEquals(found1.get.status, BookingChargeStatus.Pending, "charge1 should stay Pending after failure")
      assertEquals(found2.get.status, BookingChargeStatus.Expired, "charge2 should be Expired despite charge1 failure")
      assert(cancelled.contains(charge2.chargeId))
      assert(!cancelled.contains(charge1.chargeId))
  }
