package com.locarya.domain.models

class PaymentMethodSpec extends munit.FunSuite:

  test("PixManual encodes to PIX_MANUAL"):
    assertEquals(PaymentMethod.encode(PaymentMethod.PixManual), "PIX_MANUAL")

  test("PixAsaas encodes to PIX_ASAAS"):
    assertEquals(PaymentMethod.encode(PaymentMethod.PixAsaas), "PIX_ASAAS")

  test("PIX_MANUAL decodes to PixManual"):
    assertEquals(PaymentMethod.decode("PIX_MANUAL"), Right(PaymentMethod.PixManual))

  test("PIX_ASAAS decodes to PixAsaas"):
    assertEquals(PaymentMethod.decode("PIX_ASAAS"), Right(PaymentMethod.PixAsaas))

  test("unknown string decodes to Left(InvalidPayment)"):
    val result = PaymentMethod.decode("credit_card")
    assert(result.isLeft)
    result match
      case Left(InvalidPayment(msg)) => assert(msg.contains("credit_card"))
      case _                         => fail("expected InvalidPayment")
