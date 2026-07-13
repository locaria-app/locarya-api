package com.locarya.domain.models

import java.time.Instant
import munit.FunSuite

class PaymentSpec extends FunSuite:

  private val bookingId = BookingId.generate
  private val paidAt    = Instant.parse("2026-06-19T10:00:00Z")

  test("Payment.create succeeds with positive amount") {
    val result = Payment.create(PaymentId.generate, bookingId, BigDecimal("150.00"), PaymentMethod.PixManual, None, paidAt)
    assert(result.isRight, result)
    val p = result.toOption.get
    assertEquals(p.amount.amount, BigDecimal("150.00"))
    assertEquals(p.method, PaymentMethod.PixManual)
    assertEquals(p.status, PaymentStatus.Confirmed)
    assertEquals(p.note, None)
    assertEquals(p.bookingId, bookingId)
  }

  test("Payment.create succeeds with optional note") {
    val result = Payment.create(PaymentId.generate, bookingId, BigDecimal("200.00"), PaymentMethod.PixManual, Some("partial"), paidAt)
    assert(result.isRight)
    assertEquals(result.toOption.get.note, Some("partial"))
  }

  test("Payment.create fails with zero amount") {
    val result = Payment.create(PaymentId.generate, bookingId, BigDecimal("0"), PaymentMethod.PixManual, None, paidAt)
    assert(result.isLeft)
  }

  test("Payment.create fails with negative amount") {
    val result = Payment.create(PaymentId.generate, bookingId, BigDecimal("-10.00"), PaymentMethod.PixManual, None, paidAt)
    assert(result.isLeft)
  }

  test("PaymentMethod.encode returns PIX_MANUAL") {
    assertEquals(PaymentMethod.encode(PaymentMethod.PixManual), "PIX_MANUAL")
  }

  test("PaymentStatus.Confirmed is the only status for manual payments") {
    val result = Payment.create(PaymentId.generate, bookingId, BigDecimal("50.00"), PaymentMethod.PixManual, None, paidAt)
    assertEquals(result.toOption.get.status, PaymentStatus.Confirmed)
  }
