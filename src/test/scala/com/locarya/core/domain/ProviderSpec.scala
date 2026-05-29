package com.locarya.core.domain

import munit.FunSuite

class ProviderSpec extends FunSuite {

  test("create Provider with TaxId (CNPJ) succeeds") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val result = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil",
      city = "São Paulo",
      state = "SP"
    )

    assert(result.isRight, "Should create Provider with CNPJ-based TaxId")
    result.foreach { provider =>
      assertEquals(provider.email, email)
      assertEquals(provider.taxId, taxId)
      assert(provider.taxId.isCNPJ)
      assertEquals(provider.businessName, "Festa Fácil Locações")
      assertEquals(provider.tradeName, "Festa Fácil")
      assertEquals(provider.city, "São Paulo")
      assertEquals(provider.state, "SP")
    }
  }

  test("create Provider with TaxId (CPF) succeeds") {
    val email = Email.fromString("individual@example.com").toOption.get
    val cpf = CPF.fromString("123.456.789-09").toOption.get
    val taxId = TaxId.fromCPF(cpf)

    val result = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "João Silva Eventos",
      tradeName = "JS Eventos",
      city = "Rio de Janeiro",
      state = "RJ"
    )

    assert(result.isRight, "Should create Provider with CPF-based TaxId")
    result.foreach { provider =>
      assertEquals(provider.email, email)
      assertEquals(provider.taxId, taxId)
      assert(provider.taxId.isCPF)
      assertEquals(provider.businessName, "João Silva Eventos")
      assertEquals(provider.tradeName, "JS Eventos")
      assertEquals(provider.city, "Rio de Janeiro")
      assertEquals(provider.state, "RJ")
    }
  }

  test("reject Provider with empty city") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val resultEmpty = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil",
      city = "",
      state = "SP"
    )

    assert(resultEmpty.isLeft, "Should reject empty city")
    resultEmpty.left.foreach { error =>
      assertEquals(error, InvalidProvider("City cannot be empty"))
    }

    val resultBlank = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil",
      city = "   ",
      state = "SP"
    )

    assert(resultBlank.isLeft, "Should reject blank city")
    resultBlank.left.foreach { error =>
      assertEquals(error, InvalidProvider("City cannot be empty"))
    }
  }

  test("reject Provider with empty state") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val resultEmpty = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil",
      city = "São Paulo",
      state = ""
    )

    assert(resultEmpty.isLeft, "Should reject empty state")
    resultEmpty.left.foreach { error =>
      assertEquals(error, InvalidProvider("State cannot be empty"))
    }

    val resultBlank = Provider.create(
      id = ProviderId.generate,
      email = email,
      taxId = taxId,
      businessName = "Festa Fácil Locações",
      tradeName = "Festa Fácil",
      city = "São Paulo",
      state = "   "
    )

    assert(resultBlank.isLeft, "Should reject blank state")
    resultBlank.left.foreach { error =>
      assertEquals(error, InvalidProvider("State cannot be empty"))
    }
  }
}
