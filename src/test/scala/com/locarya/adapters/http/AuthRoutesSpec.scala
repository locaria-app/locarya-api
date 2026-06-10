package com.locarya.adapters.http

import cats.effect.IO
import com.locarya.adapters.http.middleware.CorrelationIdMiddleware
import com.locarya.domain.services.ProviderServiceImpl
import com.locarya.helpers.InMemoryProviderRepository
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AuthRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private def makeRoutes: IO[HttpRoutes[IO]] =
    InMemoryProviderRepository.make[IO].map { repo =>
      val svc = ProviderServiceImpl[IO](repo)
      AuthRoutes.routes[IO](svc)
    }

  private val validBody: String =
    """{
      "email":    "joao@example.com",
      "password": "securepassword123",
      "name":     "João Brinquedos",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  test("POST /auth/signup with valid body returns 201") {
    for
      routes   <- makeRoutes
      request   = Request[IO](Method.POST, uri"/auth/signup")
                    .withEntity(validBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.Created)
  }

  test("POST /auth/signup response body contains providerId and storefrontSlug") {
    for
      routes   <- makeRoutes
      request   = Request[IO](Method.POST, uri"/auth/signup")
                    .withEntity(validBody)
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
      request  = Request[IO](Method.POST, uri"/auth/signup")
                   .withEntity(validBody)
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
      req      = Request[IO](Method.POST, uri"/auth/signup")
                   .withEntity(validBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      _       <- routes.orNotFound(req)
      resp2   <- routes.orNotFound(req)
    yield assertEquals(resp2.status, Status.Conflict)
  }

  test("POST /auth/signup with malformed JSON returns 400") {
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/auth/signup")
                   .withEntity("""{"email": "missing-fields"}""")
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with invalid email returns 400") {
    val badBody = validBody.replace("joao@example.com", "not-an-email")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /auth/signup with password shorter than 8 chars returns 400") {
    val badBody = validBody.replace("securepassword123", "short")
    for
      routes  <- makeRoutes
      request  = Request[IO](Method.POST, uri"/auth/signup")
                   .withEntity(badBody)
                   .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- routes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }
