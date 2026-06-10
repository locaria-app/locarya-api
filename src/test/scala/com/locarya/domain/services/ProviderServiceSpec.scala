package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.{ProviderService, SignupRequest}
import com.locarya.helpers.InMemoryProviderRepository
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class ProviderServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private def makeService: IO[ProviderService[IO]] =
    InMemoryProviderRepository.make[IO].map(ProviderServiceImpl[IO](_))

  private val validRequest = SignupRequest(
    email    = "joao@example.com",
    password = "securepassword123",
    name     = "João Brinquedos",
    city     = "São Paulo",
    state    = "SP",
    cpf      = None,
    cnpj     = Some("11.222.333/0001-81")
  )

  test("signup with valid request returns SignupResult with a providerId") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest)
    yield
      assert(result.providerId.value.nonEmpty)
      assert(result.storefrontSlug.value.nonEmpty)
  }

  test("signup stores provider with Freemium plan") {
    for
      repo   <- InMemoryProviderRepository.make[IO]
      svc     = ProviderServiceImpl[IO](repo)
      result <- svc.signup(validRequest)
      stored <- repo.findById(result.providerId)
    yield assertEquals(stored.map(_.plan), Some(Plan.Freemium))
  }

  test("signup hashes password — stored hash is not the plain-text password") {
    for
      repo   <- InMemoryProviderRepository.make[IO]
      svc     = ProviderServiceImpl[IO](repo)
      result <- svc.signup(validRequest)
      stored <- repo.findById(result.providerId)
    yield
      val hash = stored.map(_.passwordHash).getOrElse("")
      assertNotEquals(hash, validRequest.password)
      assert(hash.startsWith("$2"), s"Expected bcrypt hash (starts with $$2), got: $hash")
  }

  test("signup generates a storefront slug for the provider") {
    val SlugPattern = "^[a-z0-9][a-z0-9-]*-[0-9a-f]{6}$".r
    for
      svc    <- makeService
      result <- svc.signup(validRequest)
    yield
      assert(
        SlugPattern.matches(result.storefrontSlug.value),
        s"Slug '${result.storefrontSlug.value}' does not match expected pattern"
      )
  }

  test("two signups with different emails produce different slugs") {
    for
      svc  <- makeService
      r1   <- svc.signup(validRequest)
      r2   <- svc.signup(validRequest.copy(
                email = "maria@example.com",
                cpf   = Some("123.456.789-09"),
                cnpj  = None
              ))
    yield assertNotEquals(r1.storefrontSlug.value, r2.storefrontSlug.value)
  }

  test("signup with duplicate email raises SignupError.DuplicateEmail") {
    for
      svc    <- makeService
      _      <- svc.signup(validRequest)
      result <- svc.signup(validRequest).attempt
    yield result match
      case Left(SignupError.DuplicateEmail(email)) =>
        assertEquals(email, "joao@example.com")
      case other =>
        fail(s"Expected DuplicateEmail but got: $other")
  }

  test("signup with invalid email raises SignupError.InvalidInput") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest.copy(email = "not-an-email")).attempt
    yield assert(result.isLeft, "Expected validation failure for bad email")
  }

  test("signup with password shorter than 8 chars raises SignupError.InvalidInput") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest.copy(password = "short")).attempt
    yield result match
      case Left(SignupError.InvalidInput(InvalidPassword(_))) => ()
      case other => fail(s"Expected InvalidPassword but got: $other")
  }

  test("signup with no taxId raises SignupError.InvalidInput") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest.copy(cpf = None, cnpj = None)).attempt
    yield assert(result.isLeft, "Expected failure with no taxId")
  }

  test("signup with both CPF and CNPJ raises SignupError.InvalidInput") {
    for
      svc    <- makeService
      result <- svc.signup(
                  validRequest.copy(
                    cpf  = Some("123.456.789-09"),
                    cnpj = Some("11.222.333/0001-81")
                  )
                ).attempt
    yield assert(result.isLeft, "Expected failure with both CPF and CNPJ")
  }

  test("signup with CPF (individual provider) succeeds") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest.copy(
                  email = "individual@example.com",
                  cpf   = Some("123.456.789-09"),
                  cnpj  = None
                ))
    yield assert(result.providerId.value.nonEmpty)
  }

  test("signup with empty name raises SignupError.InvalidInput") {
    for
      svc    <- makeService
      result <- svc.signup(validRequest.copy(name = "")).attempt
    yield assert(result.isLeft, "Expected failure with empty name")
  }
