package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryProviderRepository
import munit.CatsEffectSuite

class ProviderRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ProviderRepository[IO]] =
    InMemoryProviderRepository.make[IO]

  private def makeProvider(
    email: String = "provider@example.com",
    storefrontSlug: StorefrontSlug = StorefrontSlug.fromString("test-slug-000000").toOption.get
  ): Provider =
    Provider.create(
      id             = ProviderId.generate,
      email          = Email.fromString(email).toOption.get,
      taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
      businessName   = "Test Locações",
      tradeName      = "Test",
      city           = "São Paulo",
      state          = "SP",
      storefrontSlug = storefrontSlug
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

  test("findBySlug returns Some for matching provider") {
    val slug = StorefrontSlug.fromString("festa-facil-abc123").toOption.get
    for
      repo  <- makeRepo
      p      = makeProvider(storefrontSlug = slug)
      _     <- repo.create(p)
      found <- repo.findBySlug(slug)
    yield assertEquals(found, Some(p))
  }

  test("findBySlug returns None when slug not found") {
    val slug = StorefrontSlug.fromString("nonexistent-aabbcc").toOption.get
    for
      repo  <- makeRepo
      found <- repo.findBySlug(slug)
    yield assertEquals(found, None)
  }

  test("findBySlug distinguishes between different slugs") {
    val slug1 = StorefrontSlug.fromString("slug-one-aaa111").toOption.get
    val slug2 = StorefrontSlug.fromString("slug-two-bbb222").toOption.get
    for
      repo  <- makeRepo
      p1     = makeProvider("p1@example.com", storefrontSlug = slug1)
      p2     = makeProvider("p2@example.com", storefrontSlug = slug2)
      _     <- repo.create(p1)
      _     <- repo.create(p2)
      found <- repo.findBySlug(slug1)
    yield assertEquals(found, Some(p1))
  }

  test("updateStoreConfig stores new config and findById reflects the update") {
    val config = StoreConfig(primaryColor = Some("#FF0000"), tagline = Some("Festa!"))
    for
      repo    <- makeRepo
      p        = makeProvider()
      _       <- repo.create(p)
      updated <- repo.updateStoreConfig(p.id, config)
      found   <- repo.findById(p.id)
    yield
      assertEquals(updated.storeConfig, config)
      assertEquals(found.map(_.storeConfig), Some(config))
  }

  test("updateStoreConfig raises when provider is not found") {
    val config = StoreConfig(primaryColor = Some("#123456"))
    for
      repo   <- makeRepo
      result <- repo.updateStoreConfig(ProviderId.generate, config).attempt
    yield assert(result.isLeft, "Expected failure when updating non-existent provider")
  }
