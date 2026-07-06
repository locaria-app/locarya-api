package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, ProviderServiceImpl, StorefrontServiceImpl}
import com.locarya.helpers.{
  InMemoryComboRepository,
  InMemoryItemImageRepository,
  InMemoryItemRepository,
  InMemoryProviderRepository
}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class DashboardProviderRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-provider-routes"

  private case class Ctx(
    authRoutes:       HttpRoutes[IO],
    providerRoutes:   HttpRoutes[IO],
    storefrontRoutes: HttpRoutes[IO],
    providerRepo:     InMemoryProviderRepository[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> providerRoutes <+> storefrontRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo    <- InMemoryProviderRepository.make[IO]
      itemRepo        <- InMemoryItemRepository.make[IO]
      imageRepo       <- InMemoryItemImageRepository.make[IO]
      comboRepo       <- InMemoryComboRepository.make[IO]
      providerSvc      = ProviderServiceImpl[IO](providerRepo)
      authSvc          = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      storefrontSvc    = StorefrontServiceImpl[IO](providerRepo, itemRepo, imageRepo, comboRepo)
      auth             = AuthRoutes.routes[IO](providerSvc, authSvc)
      providerRoutes   = DashboardProviderRoutes.routes[IO](providerSvc, testJwtSecret)
      storefrontRoutes = StorefrontRoutes.routes[IO](storefrontSvc)
    yield Ctx(auth, providerRoutes, storefrontRoutes, providerRepo)

  private val signupBody =
    """{
      "email":    "locador@provider-routes.com",
      "password": "Securepass123",
      "name":     "Locador Provider",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@provider-routes.com","password":"Securepass123"}"""

  private case class Auth(token: String, id: String, slug: String)

  private def signupAndLogin(ctx: Ctx): IO[Auth] =
    val signupReq = Request[IO](Method.POST, uri"/api/v1/auth/signup")
      .withEntity(signupBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    val loginReq  = Request[IO](Method.POST, uri"/api/v1/auth/login")
      .withEntity(loginBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    for
      _         <- ctx.allRoutes.orNotFound(signupReq)
      loginResp <- ctx.allRoutes.orNotFound(loginReq)
      body      <- loginResp.as[String]
      json       = parse(body).toOption.get
      token      = json.hcursor.downField("token").as[String].toOption.get
      id         = json.hcursor.downField("id").as[String].toOption.get
      slug       = json.hcursor.downField("storefrontSlug").as[String].toOption.get
    yield Auth(token, id, slug)

  private def authHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private def patchStoreConfig(ctx: Ctx, body: String, token: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.PATCH, uri"/api/v1/dashboard/store-config")
        .withEntity(body)
        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    )

  private def getStorefront(ctx: Ctx, slug: String): IO[Response[IO]] =
    val uri = Uri.unsafeFromString(s"/api/v1/storefront/$slug")
    ctx.allRoutes.orNotFound(Request[IO](Method.GET, uri))

  // ── Auth guard ───────────────────────────────────────────────────────────────

  test("PATCH /dashboard/store-config returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.PATCH, uri"/api/v1/dashboard/store-config")
                  .withEntity("""{"primaryColor":"#FF0000"}""")
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
              )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  // ── Partial update ───────────────────────────────────────────────────────────

  test("PATCH /dashboard/store-config partial update preserves omitted fields") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      // Establish initial state with two fields
      _    <- patchStoreConfig(ctx, """{"primaryColor":"#111111","logoUrl":"https://logo.com"}""", auth.token)
      // Patch only primaryColor; logoUrl must survive
      resp <- patchStoreConfig(ctx, """{"primaryColor":"#FF0000"}""", auth.token)
      body <- resp.as[String]
      json  = parse(body).toOption.get
      pid   = ProviderId.fromString(auth.id).toOption.get
      stored <- ctx.providerRepo.findById(pid)
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("primaryColor").as[Option[String]].toOption.flatten, Some("#FF0000"))
      assertEquals(stored.get.storeConfig.logoUrl, Some("https://logo.com"))
  }

  // ── Null clears field ────────────────────────────────────────────────────────

  test("PATCH /dashboard/store-config with null clears the field") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      // Set a value first
      _    <- patchStoreConfig(ctx, """{"primaryColor":"#FF0000"}""", auth.token)
      // Clear it with explicit null
      resp <- patchStoreConfig(ctx, """{"primaryColor":null}""", auth.token)
      body <- resp.as[String]
      json  = parse(body).toOption.get
      pid   = ProviderId.fromString(auth.id).toOption.get
      stored <- ctx.providerRepo.findById(pid)
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("primaryColor").as[Option[String]].toOption, Some(None))
      assertEquals(stored.get.storeConfig.primaryColor, None)
  }

  // ── Storefront reflection ────────────────────────────────────────────────────

  test("PATCH /dashboard/store-config updated config is reflected in GET /storefront/:slug") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      pResp  <- patchStoreConfig(ctx, """{"primaryColor":"#ABCDEF","tagline":"Party time!"}""", auth.token)
      _      <- IO(assertEquals(pResp.status, Status.Ok))
      sfResp <- getStorefront(ctx, auth.slug)
      sfBody <- sfResp.as[String]
      sfJson  = parse(sfBody).toOption.get
    yield
      assertEquals(sfResp.status, Status.Ok)
      val config = sfJson.hcursor.downField("config")
      assertEquals(config.downField("primaryColor").as[Option[String]].toOption.flatten, Some("#ABCDEF"))
      assertEquals(config.downField("tagline").as[Option[String]].toOption.flatten, Some("Party time!"))
  }
