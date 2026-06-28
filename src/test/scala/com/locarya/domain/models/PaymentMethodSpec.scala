package com.locarya.domain.models

class PaymentMethodSpec extends munit.FunSuite:

  test("PixManual encodes to pix_manual"):
    assertEquals(PaymentMethod.encode(PaymentMethod.PixManual), "pix_manual")

  test("PixAsaas encodes to pix_asaas"):
    assertEquals(PaymentMethod.encode(PaymentMethod.PixAsaas), "pix_asaas")

  test("pix_manual decodes to PixManual"):
    assertEquals(PaymentMethod.decode("pix_manual"), Right(PaymentMethod.PixManual))

  test("pix_asaas decodes to PixAsaas"):
    assertEquals(PaymentMethod.decode("pix_asaas"), Right(PaymentMethod.PixAsaas))

  test("unknown string decodes to Left(InvalidPayment)"):
    val result = PaymentMethod.decode("credit_card")
    assert(result.isLeft)
    result match
      case Left(InvalidPayment(msg)) => assert(msg.contains("credit_card"))
      case _                         => fail("expected InvalidPayment")
