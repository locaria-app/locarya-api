package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AsaasOnboardingServiceImpl, AuthServiceImpl, ProviderServiceImpl, StorefrontServiceImpl}
import com.locarya.helpers.{InMemoryComboRepository, InMemoryItemImageRepository, InMemoryItemRepository, InMemoryProviderRepository, StubAsaasGateway}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class DashboardAsaasRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-asaas-routes"

  private case class Ctx(
    authRoutes:       HttpRoutes[IO],
    asaasRoutes:      HttpRoutes[IO],
    storefrontRoutes: HttpRoutes[IO],
    providerRepo:     InMemoryProviderRepository[IO],
    asaasGateway:     StubAsaasGateway[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> asaasRoutes <+> storefrontRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo    <- InMemoryProviderRepository.make[IO]
      itemRepo        <- InMemoryItemRepository.make[IO]
      imageRepo       <- InMemoryItemImageRepository.make[IO]
      comboRepo       <- InMemoryComboRepository.make[IO]
      asaasGateway    <- StubAsaasGateway.make[IO]("stub-wallet-id-123")
      providerSvc      = ProviderServiceImpl[IO](providerRepo)
      authSvc          = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      onboardingSvc    = AsaasOnboardingServiceImpl[IO](providerRepo, asaasGateway)
      storefrontSvc    = StorefrontServiceImpl[IO](providerRepo, itemRepo, imageRepo, comboRepo)
      auth             = AuthRoutes.routes[IO](providerSvc, authSvc)
      asaasRoutes      = DashboardAsaasRoutes.routes[IO](onboardingSvc, testJwtSecret)
      storefrontRoutes = StorefrontRoutes.routes[IO](storefrontSvc)
    yield Ctx(auth, asaasRoutes, storefrontRoutes, providerRepo, asaasGateway)

  private val signupBody =
    """{
      "email":    "locador@asaas-routes.com",
      "password": "securepassword123",
      "name":     "Asaas Provider",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@asaas-routes.com","password":"securepassword123"}"""

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

  private def postOnboarding(ctx: Ctx, token: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.POST, uri"/api/v1/dashboard/asaas/onboarding")
        .withHeaders(authHeader(token))
    )

  private def getStorefront(ctx: Ctx, slug: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/storefront/$slug"))
    )

  // ── Auth guard ──────────────────────────────────────────────────────────────

  test("POST /dashboard/asaas/onboarding returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.POST, uri"/api/v1/dashboard/asaas/onboarding")
              )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  // ── Successful onboarding ───────────────────────────────────────────────────

  test("POST /dashboard/asaas/onboarding persists walletId and returns 200") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      resp   <- postOnboarding(ctx, auth.token)
      body   <- resp.as[String]
      json    = parse(body).toOption.get
      pid     = ProviderId.fromString(auth.id).toOption.get
      stored <- ctx.providerRepo.findById(pid)
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("walletId").as[String].toOption, Some("stub-wallet-id-123"))
      assertEquals(stored.flatMap(_.walletId), Some("stub-wallet-id-123"))
  }

  // ── Idempotency ─────────────────────────────────────────────────────────────

  test("POST /dashboard/asaas/onboarding is idempotent — Asaas called only once") {
    for
      ctx   <- makeCtx
      auth  <- signupAndLogin(ctx)
      resp1 <- postOnboarding(ctx, auth.token)
      resp2 <- postOnboarding(ctx, auth.token)
      body1 <- resp1.as[String]
      body2 <- resp2.as[String]
      json1  = parse(body1).toOption.get
      json2  = parse(body2).toOption.get
      calls <- ctx.asaasGateway.callCount
    yield
      assertEquals(resp1.status, Status.Ok)
      assertEquals(resp2.status, Status.Ok)
      assertEquals(
        json1.hcursor.downField("walletId").as[String].toOption,
        json2.hcursor.downField("walletId").as[String].toOption
      )
      assertEquals(calls, 1)
  }

  // ── onlinePaymentEnabled in StorefrontResponse ───────────────────────────

  test("GET /storefront/:slug returns onlinePaymentEnabled=false before onboarding") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      resp   <- getStorefront(ctx, auth.slug)
      body   <- resp.as[String]
      json    = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("onlinePaymentEnabled").as[Boolean].toOption, Some(false))
  }

  test("GET /storefront/:slug returns onlinePaymentEnabled=true after onboarding for Premium provider") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      pid       = ProviderId.fromString(auth.id).toOption.get
      provider <- ctx.providerRepo.findById(pid).map(_.get)
      _        <- ctx.providerRepo.update(provider.withPlanTier(PlanTier.Premium))
      _        <- postOnboarding(ctx, auth.token)
      resp     <- getStorefront(ctx, auth.slug)
      body     <- resp.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("onlinePaymentEnabled").as[Boolean].toOption, Some(true))
  }
