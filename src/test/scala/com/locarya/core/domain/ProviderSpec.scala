package com.locarya.core.domain

import munit.FunSuite

class ProviderSpec extends FunSuite {

  test("create Provider with valid data succeeds") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get

    val result = Provider.create(
      id = ProviderId.generate,
      email = email,
      cnpj = cnpj,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil"
    )

    assert(result.isRight, "Should create Provider with valid data")
    result.foreach { provider =>
      assertEquals(provider.email, email)
      assertEquals(provider.cnpj, cnpj)
      assertEquals(provider.businessName, "Festa Fácil Locações")
      assertEquals(provider.tradeName, "Festa Fácil")
    }
  }
}
