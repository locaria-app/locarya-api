package com.locarya.adapters.http.middleware

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

import java.time.Instant

class AuthMiddlewareSpec extends CatsEffectSuite:

  private val testSecret = "test-middleware-secret"

  private def makeToken(
    secret:    String       = testSecret,
    expireAt:  Option[Long] = None
  ): String =
    val now = Instant.now().getEpochSecond
    JwtCirce.encode(
      JwtClaim(
        content    = """{"providerId":"test-id","email":"test@example.com","plan":"FREEMIUM"}""",
        issuedAt   = Some(now),
        expiration = Some(expireAt.getOrElse(now + 86400L))
      ),
      secret,
      JwtAlgorithm.HS256
    )

  private val protectedRoute: HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "dashboard" / "home" => Ok("protected content")

  // Cycle 1: Tracer bullet — valid token passes through
  test("request with valid Bearer token passes through to inner routes") {
    val token   = makeToken()
    val wrapped = AuthMiddleware(testSecret)(protectedRoute)
    val request = Request[IO](Method.GET, uri"/dashboard/home")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    for
      response <- wrapped.orNotFound(request)
    yield assertEquals(response.status, Status.Ok)
  }

  // Cycle 2: Missing Authorization header returns 401
  test("request without Authorization header returns 401") {
    val wrapped = AuthMiddleware(testSecret)(protectedRoute)
    val request = Request[IO](Method.GET, uri"/dashboard/home")
    for
      response <- wrapped.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  // Cycle 3: Tampered token returns 401
  test("request with tampered token returns 401") {
    val token   = makeToken() + "TAMPERED"
    val wrapped = AuthMiddleware(testSecret)(protectedRoute)
    val request = Request[IO](Method.GET, uri"/dashboard/home")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    for
      response <- wrapped.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  // Cycle 4: Expired token returns 401
  test("request with expired token returns 401") {
    val pastExp = Instant.now().getEpochSecond - 1L
    val token   = makeToken(expireAt = Some(pastExp))
    val wrapped = AuthMiddleware(testSecret)(protectedRoute)
    val request = Request[IO](Method.GET, uri"/dashboard/home")
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
    for
      response <- wrapped.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }
