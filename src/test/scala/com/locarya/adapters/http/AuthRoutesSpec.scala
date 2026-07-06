package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.adapters.http.middleware.{AuthMiddleware, CorrelationIdMiddleware}
import com.locarya.domain.services.{AuthServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.InMemoryProviderRepository
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AuthRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-for-routes"

  private def makeRoutes: IO[HttpRoutes[IO]] =
    InMemoryProviderRepository.make[IO].map { repo =>
      val providerSvc = ProviderServiceImpl[IO](repo)
      val authSvc     = AuthServiceImpl[IO](repo, testJwtSecret)
      AuthRoutes.routes[IO](providerSvc, authSvc)
    }

  private val validSignupBody: String =
    """{
      "email":    "joao@example.com",
      "password": "Securepass123",
      "name":     "João Brinquedos",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val validLoginBody: String =
    """{"email":"joao@example.com","password":"Securepass123"}"""

  private val dashboardRoute: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "dashboard" / "home" => Ok("protected")

  // ── Signup tests (unchanged behaviour) ─────────────────────────────────────

  test("POST /auth/signup with valid body returns 201") {
    for
      routes   <- makeRoutes
      request   = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                    .withEntity(validSignupBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.Created)
  }

  test("POST /auth/signup response body contains providerId and storefrontSlug") {
    for
      routes   <- makeRoutes
      request   = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                    .withEntity(validSignupBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assert(json.hcursor.downField("providerId").focus.isDefined,    "response must contain providerId")
      assert(json.hcursor.downField("storefrontSlug").focus.isDefined, "response must contain storefrontSlug")
      val slug = json.hcursor.downField("storefrontSlug").as[String].toOption.get
      assert(slug.nonEmpty, s"storefrontSlug must not be empty: $slug")
  }

  test("POST /auth/signup echoes X-Correlation-ID header") {
    val correlationId = "test-signup-correlation-id"
    for
      routes  <- makeRoutes
      wrapped  = CorrelationIdMiddleware(routes)
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(validSignupBody)
                   .withHeaders(
                     Header.Raw(ci"Content-Type",    "application/json"),
                     Header.Raw(ci"X-Correlation-ID", correlationId)
                   )
      response <- wrapped.orNotFound(request)
    yield
      val returned = response.headers.get(ci"X-Correlation-ID").map(_.head.value)
      assertEquals(returned, Some(correlationId))
  }

  test("POST /auth/signup with duplicate email returns 409 Conflict") {
    for
      routes  <- makeRoutes
      req      = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(validSignupBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      _       <- routes.orNotFound(req)
      resp2   <- routes.orNotFound(req)
    yield assertEquals(resp2.status, Status.Conflict)
  }

  test("POST /auth/signup with duplicate CPF returns 409 Conflict") {
    val cpfBody1 =
      """{
        "email":    "cpf-provider1@example.com",
        "password": "Securepass123",
        "name":     "Provider CPF One",
        "city":     "São Paulo",
        "state":    "SP",
        "cpf":      "529.982.247-25"
      }"""
    val cpfBody2 =
      """{
        "email":    "cpf-provider2@example.com",
        "password": "Securepass456",
        "name":     "Provider CPF Two",
        "city":     "Rio de Janeiro",
        "state":    "RJ",
        "cpf":      "529.982.247-25"
      }"""
    for
      routes <- makeRoutes
      req1    = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                  .withEntity(cpfBody1)
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      req2    = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                  .withEntity(cpfBody2)
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      _      <- routes.orNotFound(req1)
      resp2  <- routes.orNotFound(req2)
    yield assertEquals(resp2.status, Status.Conflict)
  }

  test("POST /auth/signup with duplicate CNPJ returns 409 Conflict") {
    val cnpjBody2 = validSignupBody.replace("joao@example.com", "joao2@example.com")
    for
      routes <- makeRoutes
      req1    = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                  .withEntity(validSignupBody)
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      req2    = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                  .withEntity(cnpjBody2)
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      _      <- routes.orNotFound(req1)
      resp2  <- routes.orNotFound(req2)
    yield assertEquals(resp2.status, Status.Conflict)
  }

  test("POST /auth/signup with malformed JSON returns 400") {
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity("""{"email": "missing-fields"}""")
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with invalid email returns 400") {
    val badBody = validSignupBody.replace("joao@example.com", "not-an-email")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with password shorter than 8 chars returns 400") {
    val badBody = validSignupBody.replace("Securepass123", "short")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with password without uppercase returns 400") {
    val badBody = validSignupBody.replace("Securepass123", "securepass123")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with password without lowercase returns 400") {
    val badBody = validSignupBody.replace("Securepass123", "SECUREPASS123")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with password without digit returns 400") {
    val badBody = validSignupBody.replace("Securepass123", "SecurepassX!")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  // ── Login tests ─────────────────────────────────────────────────────────────

  private def signupThenLogin(routes: HttpRoutes[IO]): IO[Response[IO]] =
    val signupReq = Request[IO](Method.POST, uri"/api/v1/auth/signup")
      .withEntity(validSignupBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    val loginReq = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(validLoginBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    routes.orNotFound(signupReq) >> routes.orNotFound(loginReq)

  test("POST /auth/login with valid credentials returns 200") {
    for
      routes   <- makeRoutes
      response <- signupThenLogin(routes)
    yield assertEquals(response.status, Status.Ok)
  }

  test("POST /auth/login response body contains token, id, name, email, planId, storefrontSlug") {
    for
      routes   <- makeRoutes
      response <- signupThenLogin(routes)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      val c = json.hcursor
      assert(c.downField("token").as[String].isRight,          "must contain token")
      assert(c.downField("id").as[String].isRight,             "must contain id")
      assert(c.downField("name").as[String].isRight,           "must contain name")
      assert(c.downField("email").as[String].isRight,          "must contain email")
      assert(c.downField("planId").as[String].isRight,         "must contain planId")
      assert(c.downField("storefrontSlug").as[String].isRight, "must contain storefrontSlug")
      val token = c.downField("token").as[String].toOption.get
      assert(token.nonEmpty, "token must not be empty")
      assertEquals(c.downField("email").as[String].toOption, Some("joao@example.com"))
  }

  test("POST /auth/login with wrong password returns 401") {
    val wrongLoginBody = """{"email":"joao@example.com","password":"wrongpassword!"}"""
    for
      routes    <- makeRoutes
      signupReq  = Request[IO](Method.POST, uri"/api/v1/auth/signup")
                     .withEntity(validSignupBody)
                     .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      _         <- routes.orNotFound(signupReq)
      loginReq   = Request[IO](Method.POST, uri"/api/v1/auth/login")
                     .withEntity(wrongLoginBody)
                     .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response  <- routes.orNotFound(loginReq)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("POST /auth/login with unknown email returns 401") {
    val unknownBody = """{"email":"nobody@example.com","password":"Securepass123"}"""
    for
      routes   <- makeRoutes
      loginReq  = Request[IO](Method.POST, uri"/api/v1/auth/login")
                    .withEntity(unknownBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(loginReq)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  // ── Integration tests ───────────────────────────────────────────────────────

  test("JWT from login can access protected dashboard route") {
    for
      routes       <- makeRoutes
      loginResp    <- signupThenLogin(routes)
      body         <- loginResp.as[String]
      json          = parse(body).toOption.get
      token         = json.hcursor.downField("token").as[String].toOption.get
      allRoutes     = routes <+> AuthMiddleware(testJwtSecret)(dashboardRoute)
      dashReq       = Request[IO](Method.GET, uri"/dashboard/home")
                        .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      dashResp     <- allRoutes.orNotFound(dashReq)
    yield assertEquals(dashResp.status, Status.Ok)
  }

  test("access to protected route without JWT returns 401") {
    for
      routes    <- makeRoutes
      allRoutes  = routes <+> AuthMiddleware(testJwtSecret)(dashboardRoute)
      dashReq    = Request[IO](Method.GET, uri"/dashboard/home")
      dashResp  <- allRoutes.orNotFound(dashReq)
    yield assertEquals(dashResp.status, Status.Unauthorized)
  }
