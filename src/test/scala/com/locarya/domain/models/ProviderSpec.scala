package com.locarya.domain.models

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

  test("create Provider defaults to Freemium plan") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj  = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val result = Provider.create(
      id = ProviderId.generate, email = email, taxId = taxId,
      businessName = "Festa Fácil Locações", tradeName = "Festa Fácil",
      city = "São Paulo", state = "SP"
    )

    assert(result.isRight)
    assertEquals(result.toOption.get.planTier, PlanTier.Freemium)
  }

  test("create Provider with explicit storefrontSlug stores it") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj  = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)
    val slug  = StorefrontSlug.fromString("festa-facil-abc123").toOption.get

    val result = Provider.create(
      id = ProviderId.generate, email = email, taxId = taxId,
      businessName = "Festa Fácil Locações", tradeName = "Festa Fácil",
      city = "São Paulo", state = "SP",
      storefrontSlug = slug
    )

    assert(result.isRight)
    assertEquals(result.toOption.get.storefrontSlug, slug)
  }

  test("create Provider defaults isActive to true") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj  = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val result = Provider.create(
      id = ProviderId.generate, email = email, taxId = taxId,
      businessName = "Festa Fácil Locações", tradeName = "Festa Fácil",
      city = "São Paulo", state = "SP"
    )

    assert(result.isRight)
    assertEquals(result.toOption.get.isActive, true)
  }

  test("create Provider with isActive = false stores it") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj  = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)

    val result = Provider.create(
      id = ProviderId.generate, email = email, taxId = taxId,
      businessName = "Festa Fácil Locações", tradeName = "Festa Fácil",
      city = "São Paulo", state = "SP",
      isActive = false
    )

    assert(result.isRight)
    assertEquals(result.toOption.get.isActive, false)
  }

  test("deactivate returns Provider copy with isActive = false") {
    val email = Email.fromString("provider@example.com").toOption.get
    val cnpj  = CNPJ.fromString("11.222.333/0001-81").toOption.get
    val taxId = TaxId.fromCNPJ(cnpj)
    val id    = ProviderId.generate

    val provider = Provider.create(
      id = id, email = email, taxId = taxId,
      businessName = "Festa Fácil Locações", tradeName = "Festa Fácil",
      city = "São Paulo", state = "SP"
    ).toOption.get

    val deactivated = provider.deactivate

    assertEquals(deactivated.isActive, false)
    assertEquals(deactivated.id, provider.id)
    assertEquals(deactivated.email, provider.email)
    assertEquals(deactivated.taxId, provider.taxId)
    assertEquals(deactivated.businessName, provider.businessName)
    assertEquals(deactivated.tradeName, provider.tradeName)
    assertEquals(deactivated.city, provider.city)
    assertEquals(deactivated.state, provider.state)
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
