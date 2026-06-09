package com.locarya.domain.models

import munit.FunSuite

class MoneySpec extends FunSuite {

  test("create Money with positive amount succeeds") {
    val result = Money.fromAmount(BigDecimal("100.00"))

    assert(result.isRight, "Should create Money with positive amount")
    result.foreach { money =>
      assertEquals(money.amount, BigDecimal("100.00"))
    }
  }

  test("create Money with zero fails") {
    val result = Money.fromAmount(BigDecimal("0"))

    assert(result.isLeft, "Should fail to create Money with zero")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAmount], "Should return InvalidAmount error")
    }
  }

  test("create Money with negative amount fails") {
    val result = Money.fromAmount(BigDecimal("-50.00"))

    assert(result.isLeft, "Should fail to create Money with negative amount")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAmount], "Should return InvalidAmount error")
    }
  }

  test("adding two Money values works") {
    val money1 = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val money2 = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = money1 + money2

    assertEquals(result.amount, BigDecimal("150.00"))
  }

  test("subtracting Money that would go negative fails") {
    val money1 = Money.fromAmount(BigDecimal("50.00")).toOption.get
    val money2 = Money.fromAmount(BigDecimal("100.00")).toOption.get

    val result = money1 - money2

    assert(result.isLeft, "Should fail when subtraction would result in negative amount")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAmount], "Should return InvalidAmount error")
    }
  }

  test("subtracting Money with valid result succeeds") {
    val money1 = Money.fromAmount(BigDecimal("100.00")).toOption.get
    val money2 = Money.fromAmount(BigDecimal("30.00")).toOption.get

    val result = money1 - money2

    assert(result.isRight, "Should succeed when subtraction is valid")
    result.foreach { money =>
      assertEquals(money.amount, BigDecimal("70.00"))
    }
  }

  test("multiplying Money by positive number works") {
    val money = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = money * 3

    assert(result.isRight, "Should succeed with positive multiplier")
    result.foreach { m =>
      assertEquals(m.amount, BigDecimal("150.00"))
    }
  }

  test("multiplying Money by zero or negative fails") {
    val money = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val resultZero = money * 0
    val resultNegative = money * -2

    assert(resultZero.isLeft, "Should fail with zero multiplier")
    assert(resultNegative.isLeft, "Should fail with negative multiplier")
  }
}
