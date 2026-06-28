package com.locarya.domain.models

import cats.effect.IO
import com.locarya.helpers.PaymentGatewayStub
import munit.CatsEffectSuite

class PaymentGatewayStubSpec extends CatsEffectSuite:

  private val bookingId     = BookingId.generate
  private val walletId      = "wlt_abc123"
  private val customerEmail = "cliente@example.com"
  private val amount        = BigDecimal("150.00")

  test("createCharge returns AsaasCharge with non-empty chargeId and paymentUrl"):
    PaymentGatewayStub.make[IO].flatMap { stub =>
      stub.createCharge(bookingId, walletId, amount, customerEmail).map { charge =>
        assert(charge.chargeId.nonEmpty, "chargeId should be non-empty")
        assert(charge.paymentUrl.nonEmpty, "paymentUrl should be non-empty")
      }
    }

  test("cancelCharge returns Unit without raising"):
    PaymentGatewayStub.make[IO].flatMap { stub =>
      stub.cancelCharge("charge_abc123").map { result =>
        assertEquals(result, ())
      }
    }

  test("createCharge call count increments per call"):
    PaymentGatewayStub.make[IO].flatMap { stub =>
      for
        _     <- stub.createCharge(bookingId, walletId, amount, customerEmail)
        _     <- stub.createCharge(bookingId, walletId, amount, customerEmail)
        count <- stub.createChargeCallCount
      yield assertEquals(count, 2)
    }

  test("cancelCharge call count increments per call"):
    PaymentGatewayStub.make[IO].flatMap { stub =>
      for
        _     <- stub.cancelCharge("charge_1")
        _     <- stub.cancelCharge("charge_2")
        count <- stub.cancelChargeCallCount
      yield assertEquals(count, 2)
    }
