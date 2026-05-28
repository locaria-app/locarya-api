package com.locarya.core.domain

import munit.FunSuite

class CPFSpec extends FunSuite {

  test("create CPF with valid format and check digits succeeds") {
    // This is a valid CPF: 123.456.789-09
    val result = CPF.fromString("123.456.789-09")

    assert(result.isRight, "Should create CPF with valid format and check digits")
    result.foreach { cpf =>
      // Should be normalized (no formatting)
      assertEquals(cpf.value, "12345678909")
    }
  }

  test("create CPF with invalid check digits fails") {
    // 123.456.789-00 has wrong check digits (should be 09)
    val result = CPF.fromString("123.456.789-00")

    assert(result.isLeft, "Should fail when check digits are invalid")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCPF], "Should return InvalidCPF error")
    }
  }

  test("create CPF with all same digits fails") {
    val result = CPF.fromString("111.111.111-11")

    assert(result.isLeft, "Should fail when all digits are the same")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCPF], "Should return InvalidCPF error")
    }
  }

  test("create CPF with wrong length fails") {
    val result = CPF.fromString("123.456.789")

    assert(result.isLeft, "Should fail when length is not 11 digits")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCPF], "Should return InvalidCPF error")
    }
  }

  test("create CPF with non-digit characters fails") {
    val result = CPF.fromString("123.456.78a-09")

    assert(result.isLeft, "Should fail when contains non-digit characters")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCPF], "Should return InvalidCPF error")
    }
  }
}
