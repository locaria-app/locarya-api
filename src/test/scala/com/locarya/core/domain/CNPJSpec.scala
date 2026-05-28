package com.locarya.core.domain

import munit.FunSuite

class CNPJSpec extends FunSuite {

  test("create CNPJ with valid format and check digits succeeds") {
    // This is a valid CNPJ: 11.222.333/0001-81
    val result = CNPJ.fromString("11.222.333/0001-81")

    assert(result.isRight, "Should create CNPJ with valid format and check digits")
    result.foreach { cnpj =>
      // Should be normalized (no formatting)
      assertEquals(cnpj.value, "11222333000181")
    }
  }

  test("create CNPJ with invalid check digits fails") {
    // 11.222.333/0001-00 has wrong check digits (should be 81)
    val result = CNPJ.fromString("11.222.333/0001-00")

    assert(result.isLeft, "Should fail when check digits are invalid")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCNPJ], "Should return InvalidCNPJ error")
    }
  }

  test("create CNPJ with all same digits fails") {
    val result = CNPJ.fromString("11.111.111/1111-11")

    assert(result.isLeft, "Should fail when all digits are the same")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCNPJ], "Should return InvalidCNPJ error")
    }
  }

  test("create CNPJ with wrong length fails") {
    val result = CNPJ.fromString("11.222.333/0001")

    assert(result.isLeft, "Should fail when length is not 14 digits")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCNPJ], "Should return InvalidCNPJ error")
    }
  }

  test("create CNPJ with non-digit characters fails") {
    val result = CNPJ.fromString("11.222.33a/0001-81")

    assert(result.isLeft, "Should fail when contains non-digit characters")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCNPJ], "Should return InvalidCNPJ error")
    }
  }
}
