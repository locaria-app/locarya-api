package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.services.{AuthServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{ImageStorageGatewayStub, InMemoryProviderRepository}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class DashboardUploadRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-uploads"

  private case class Ctx(
    authRoutes:   HttpRoutes[IO],
    uploadRoutes: HttpRoutes[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> uploadRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      providerSvc   = ProviderServiceImpl[IO](providerRepo)
      authSvc       = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      storage       = ImageStorageGatewayStub.make[IO]
      auth          = AuthRoutes.routes[IO](providerSvc, authSvc)
      uploads       = DashboardUploadRoutes.routes[IO](storage, testJwtSecret)
    yield Ctx(auth, uploads)

  private val signupBody =
    """{
      "email":    "provider@example.com",
      "password": "Securepass123",
      "name":     "Provider Teste",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody = """{"email":"provider@example.com","password":"Securepass123"}"""

  private def getToken(ctx: Ctx): IO[String] =
    val signupReq = Request[IO](Method.POST, uri"/api/v1/auth/signup")
      .withEntity(signupBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    val loginReq = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(loginBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    for
      _         <- ctx.allRoutes.orNotFound(signupReq)
      loginResp <- ctx.allRoutes.orNotFound(loginReq)
      body      <- loginResp.as[String]
      json       = parse(body).toOption.get
    yield json.hcursor.downField("token").as[String].toOption.get

  private def authHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  // ── POST /api/v1/dashboard/uploads ───────────────────────────────────────────

  test("POST /api/v1/dashboard/uploads without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/uploads")
                    .withEntity("""{"contentType":"image/jpeg"}""")
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("POST /api/v1/dashboard/uploads with valid JWT and unsupported content type returns 400") {
    for
      ctx      <- makeCtx
      token    <- getToken(ctx)
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/uploads")
                    .withEntity("""{"contentType":"image/gif"}""")
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /api/v1/dashboard/uploads with valid JWT and allowed content type returns 200 with presigned upload") {
    for
      ctx      <- makeCtx
      token    <- getToken(ctx)
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/uploads")
                    .withEntity("""{"contentType":"image/jpeg"}""")
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assert(json.hcursor.downField("objectKey").focus.isDefined, s"Expected objectKey in response: $body")
      assert(json.hcursor.downField("uploadUrl").focus.isDefined, s"Expected uploadUrl in response: $body")
      assert(json.hcursor.downField("publicUrl").focus.isDefined, s"Expected publicUrl in response: $body")
      assert(json.hcursor.downField("expiresAt").focus.isDefined, s"Expected expiresAt in response: $body")
  }
