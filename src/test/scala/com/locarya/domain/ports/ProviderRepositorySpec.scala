package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryProviderRepository
import munit.CatsEffectSuite

class ProviderRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ProviderRepository[IO]] =
    InMemoryProviderRepository.make[IO]

  private def makeProvider(email: String = "provider@example.com"): Provider =
    Provider.create(
      id = ProviderId.generate,
      email = Email.fromString(email).toOption.get,
      taxId = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
      businessName = "Test Locações",
      tradeName = "Test",
      city = "São Paulo",
      state = "SP"
    ).toOption.get

  test("create stores provider and findById retrieves it") {
    for
      repo   <- makeRepo
      p       = makeProvider()
      stored <- repo.create(p)
      found  <- repo.findById(p.id)
    yield
      assertEquals(stored, p)
      assertEquals(found, Some(p))
  }

  test("findById returns None for missing provider") {
    for
      repo  <- makeRepo
      found <- repo.findById(ProviderId.generate)
    yield assertEquals(found, None)
  }

  test("findByEmail returns Some on match") {
    for
      repo  <- makeRepo
      p      = makeProvider()
      _     <- repo.create(p)
      found <- repo.findByEmail(p.email)
    yield assertEquals(found, Some(p))
  }

  test("findByEmail returns None on miss") {
    for
      repo  <- makeRepo
      found <- repo.findByEmail(Email.fromString("missing@example.com").toOption.get)
    yield assertEquals(found, None)
  }

  test("update overwrites provider fields") {
    for
      repo    <- makeRepo
      p        = makeProvider()
      _       <- repo.create(p)
      updated  = Provider.create(p.id, p.email, p.taxId, p.businessName, p.tradeName, "Campinas", p.state).toOption.get
      saved   <- repo.update(updated)
      found   <- repo.findById(p.id)
    yield
      assertEquals(saved.city, "Campinas")
      assertEquals(found.map(_.city), Some("Campinas"))
  }

  test("create with duplicate id raises in F") {
    for
      repo   <- makeRepo
      p       = makeProvider()
      _      <- repo.create(p)
      result <- repo.create(p).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }
