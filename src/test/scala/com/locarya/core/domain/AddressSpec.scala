package com.locarya.core.domain

import munit.FunSuite

class AddressSpec extends FunSuite {

  test("create Address with valid fields succeeds") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "12345-678",
      complement = Some("Apto 45")
    )

    assert(result.isRight, "Should create Address with valid fields")
    result.foreach { address =>
      assertEquals(address.street, "Rua das Flores")
      assertEquals(address.number, "123")
      assertEquals(address.neighborhood, "Centro")
      assertEquals(address.city, "São Paulo")
      assertEquals(address.state, "SP")
      assertEquals(address.cep, "12345678") // CEP normalized
      assertEquals(address.complement, Some("Apto 45"))
    }
  }

  test("create Address with CEP without dash succeeds") {
    val result = Address.create(
      street = "Av. Paulista",
      number = "1000",
      neighborhood = "Bela Vista",
      city = "São Paulo",
      state = "SP",
      cep = "01310100",
      complement = None
    )

    assert(result.isRight, "Should create Address with CEP without dash")
    result.foreach { address =>
      assertEquals(address.cep, "01310100")
    }
  }

  test("create Address with invalid CEP format fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "1234-567", // Only 7 digits
      complement = None
    )

    assert(result.isLeft, "Should fail when CEP doesn't have 8 digits")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with empty street fails") {
    val result = Address.create(
      street = "",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "12345-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when street is empty")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with empty number fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "12345-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when number is empty")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with empty neighborhood fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "",
      city = "São Paulo",
      state = "SP",
      cep = "12345-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when neighborhood is empty")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with empty city fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "",
      state = "SP",
      cep = "12345-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when city is empty")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with invalid state code fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "XX", // Invalid state
      cep = "12345-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when state code is invalid")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }

  test("create Address with valid state codes succeeds") {
    val validStates = List("SP", "RJ", "MG", "AC", "AL", "AP", "AM", "BA", "CE", "DF",
                           "ES", "GO", "MA", "MT", "MS", "PA", "PB", "PR", "PE", "PI",
                           "RN", "RS", "RO", "RR", "SC", "SE", "TO")

    validStates.foreach { state =>
      val result = Address.create(
        street = "Rua das Flores",
        number = "123",
        neighborhood = "Centro",
        city = "São Paulo",
        state = state,
        cep = "12345-678",
        complement = None
      )

      assert(result.isRight, s"Should create Address with valid state code: $state")
    }
  }

  test("create Address without complement succeeds") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "12345-678",
      complement = None
    )

    assert(result.isRight, "Should create Address without complement")
    result.foreach { address =>
      assertEquals(address.complement, None)
    }
  }

  test("create Address with CEP containing non-digits fails") {
    val result = Address.create(
      street = "Rua das Flores",
      number = "123",
      neighborhood = "Centro",
      city = "São Paulo",
      state = "SP",
      cep = "1234A-678",
      complement = None
    )

    assert(result.isLeft, "Should fail when CEP contains non-digit characters")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidAddress], "Should return InvalidAddress error")
    }
  }
}
