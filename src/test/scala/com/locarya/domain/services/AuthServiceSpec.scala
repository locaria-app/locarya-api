package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{CapturingLogger, InMemoryProviderRepository}
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import pdi.jwt.{JwtAlgorithm, JwtCirce}

class AuthServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testSecret = "test-jwt-secret-key"

  private val signupRequest = SignupRequest(
    email    = "provider@example.com",
    password = "securepassword123",
    name     = "Test Provider",
    city     = "São Paulo",
    state    = "SP",
    cpf      = None,
    cnpj     = Some("11.222.333/0001-81")
  )

  private def makeServices: IO[(ProviderService[IO], AuthService[IO])] =
    InMemoryProviderRepository.make[IO].map { repo =>
      val providerSvc = ProviderServiceImpl[IO](repo)
      val authSvc     = AuthServiceImpl[IO](repo, testSecret)
      (providerSvc, authSvc)
    }

  private def makeServicesWithLogger(
    logger: Logger[IO]
  ): IO[(ProviderService[IO], AuthService[IO])] =
    InMemoryProviderRepository.make[IO].map { repo =>
      given Logger[IO] = logger
      val providerSvc  = ProviderServiceImpl[IO](repo)
      val authSvc      = AuthServiceImpl[IO](repo, testSecret)
      (providerSvc, authSvc)
    }

  // Cycle 1: Tracer bullet — happy path
  test("login with valid credentials returns LoginResult with non-empty token") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
    yield
      assert(result.token.nonEmpty, "token must not be empty")
      assertEquals(result.email, "provider@example.com")
  }

  // Cycle 2: LoginResult fields
  test("login returns LoginResult with correct name, plan, and storefrontSlug") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
    yield
      assertEquals(result.name, "Test Provider")
      assertEquals(result.plan, "FREEMIUM")
      assert(result.storefrontSlug.nonEmpty, "storefrontSlug must not be empty")
      assert(result.providerId.nonEmpty, "providerId must not be empty")
  }

  // Cycle 3: Wrong password
  test("login with wrong password raises AuthError.InvalidCredentials") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "wrongpassword!")).attempt
    yield result match
      case Left(AuthError.InvalidCredentials(email)) =>
        assertEquals(email, "provider@example.com")
      case other =>
        fail(s"Expected InvalidCredentials but got: $other")
  }

  // Cycle 4: Unknown email
  test("login with unknown email raises AuthError.AccountNotFound") {
    for
      svcs   <- makeServices
      (_, authSvc) = svcs
      result <- authSvc.login(LoginRequest("unknown@example.com", "anypassword")).attempt
    yield result match
      case Left(AuthError.AccountNotFound(email)) =>
        assertEquals(email, "unknown@example.com")
      case other =>
        fail(s"Expected AccountNotFound but got: $other")
  }

  // Cycle 5: JWT claims
  test("JWT contains required claims: providerId, email, plan, iat, exp") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
    yield
      val decoded = JwtCirce.decodeJson(result.token, testSecret, Seq(JwtAlgorithm.HS256))
      val json    = decoded.toOption.getOrElse(fail("JWT decode failed"))
      val c       = json.hcursor
      assert(c.downField("providerId").as[String].isRight, "token must contain providerId")
      assert(c.downField("email").as[String].isRight,      "token must contain email")
      assertEquals(c.downField("email").as[String].toOption, Some("provider@example.com"))
      assert(c.downField("plan").as[String].isRight, "token must contain plan")
      assertEquals(c.downField("plan").as[String].toOption, Some("FREEMIUM"))
      assert(c.downField("iat").as[Long].isRight, "token must contain iat")
      assert(c.downField("exp").as[Long].isRight, "token must contain exp")
  }

  // Cycle 6: JWT expiry is exactly 24h
  test("JWT expiry is 24 hours after issuance") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
    yield
      val decoded = JwtCirce.decodeJson(result.token, testSecret, Seq(JwtAlgorithm.HS256))
      val json    = decoded.toOption.getOrElse(fail("JWT decode failed"))
      val c   = json.hcursor
      val iat = c.downField("iat").as[Long].fold(e => fail(s"missing iat: $e"), identity)
      val exp = c.downField("exp").as[Long].fold(e => fail(s"missing exp: $e"), identity)
      assertEquals(exp - iat, 86400L, "JWT must expire exactly 24h after issuance")
  }

  // Cycle 7: Wrong secret fails validation
  test("token signed with different secret fails validation under that secret") {
    for
      svcs   <- makeServices
      (providerSvc, authSvc) = svcs
      _      <- providerSvc.signup(signupRequest)
      result <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
    yield
      val decoded = JwtCirce.decodeJson(result.token, "wrong-secret", Seq(JwtAlgorithm.HS256))
      assert(decoded.isFailure, "token signed with different secret must fail validation")
  }

  // Cycle 8: LoginSuccessful log event
  test("login emits LoginSuccessful log event with providerId") {
    for
      loggerPair             <- CapturingLogger.make
      (capLogger, getLogs)    = loggerPair
      svcs                   <- makeServicesWithLogger(capLogger)
      (providerSvc, authSvc)  = svcs
      _                      <- providerSvc.signup(signupRequest)
      result                 <- authSvc.login(LoginRequest("provider@example.com", "securepassword123"))
      logs                   <- getLogs
    yield
      assert(
        logs.exists(_.contains("LoginSuccessful")),
        s"Expected LoginSuccessful in logs but got: $logs"
      )
      assert(
        logs.exists(log => log.contains("LoginSuccessful") && log.contains(result.providerId)),
        s"LoginSuccessful must include providerId in logs: $logs"
      )
  }

  // Cycle 9: LoginFailed on wrong password
  test("login with wrong password emits LoginFailed log event") {
    for
      loggerPair             <- CapturingLogger.make
      (capLogger, getLogs)    = loggerPair
      svcs                   <- makeServicesWithLogger(capLogger)
      (providerSvc, authSvc)  = svcs
      _                      <- providerSvc.signup(signupRequest)
      _                      <- authSvc.login(LoginRequest("provider@example.com", "wrongpassword!")).attempt
      logs                   <- getLogs
    yield
      assert(
        logs.exists(_.contains("LoginFailed")),
        s"Expected LoginFailed in logs but got: $logs"
      )
  }

  // Cycle 10: LoginFailed on unknown email
  test("login with unknown email emits LoginFailed log event") {
    for
      loggerPair          <- CapturingLogger.make
      (capLogger, getLogs) = loggerPair
      svcs                <- makeServicesWithLogger(capLogger)
      (_, authSvc)         = svcs
      _                   <- authSvc.login(LoginRequest("unknown@example.com", "anypassword")).attempt
      logs                <- getLogs
    yield
      assert(
        logs.exists(_.contains("LoginFailed")),
        s"Expected LoginFailed in logs but got: $logs"
      )
  }
