package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, AvailabilityServiceImpl, BookingChargeServiceImpl, BookingServiceImpl, ItemServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{
  InMemoryBookingChargeRepository,
  InMemoryBookingRepository,
  InMemoryCustomerRepository,
  InMemoryItemRepository,
  InMemoryItemImageRepository,
  InMemoryComboRepository,
  InMemoryAttendantRepository,
  InMemoryNotificationEventRepository,
  InMemoryProviderRepository,
  PaymentGatewayStub
}
import io.circe.parser.parse
import java.time.LocalDate
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.apispec.openapi.circe.yaml.*

class StorefrontChargeRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-charge"
  private val date          = LocalDate.of(2026, 9, 1)
  private val totalMoney    = Money.fromAmount(BigDecimal("500.00")).toOption.get

  private case class Ctx(
    authRoutes:     HttpRoutes[IO],
    bookingRoutes:  HttpRoutes[IO],
    chargeRoutes:   HttpRoutes[IO],
    itemRoutes:     HttpRoutes[IO],
    providerRepo:   InMemoryProviderRepository[IO],
    bookingRepo:    InMemoryBookingRepository[IO],
    chargeRepo:     InMemoryBookingChargeRepository[IO],
    gateway:        PaymentGatewayStub[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> bookingRoutes <+> chargeRoutes <+> itemRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      itemRepo      <- InMemoryItemRepository.make[IO]
      imageRepo     <- InMemoryItemImageRepository.make[IO]
      comboRepo     <- InMemoryComboRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      customerRepo  <- InMemoryCustomerRepository.make[IO]
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      chargeRepo    <- InMemoryBookingChargeRepository.make[IO]
      notifRepo     <- InMemoryNotificationEventRepository.make[IO]
      gateway       <- PaymentGatewayStub.make[IO]
      providerSvc    = ProviderServiceImpl[IO](providerRepo)
      authSvc        = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      availSvc       = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      bookingSvc     = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availSvc, attendantRepo)
      chargeSvc      = BookingChargeServiceImpl[IO](providerRepo, bookingRepo, customerRepo, chargeRepo, gateway, notifRepo)
      auth           = AuthRoutes.routes[IO](providerSvc, authSvc)
      bookingRoutes  = StorefrontBookingRoutes.routes[IO](bookingSvc)
      chargeRoutes   = StorefrontChargeRoutes.routes[IO](chargeSvc)
      itemRoutes     = ItemRoutes.routes[IO](ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo), testJwtSecret)
    yield Ctx(auth, bookingRoutes, chargeRoutes, itemRoutes, providerRepo, bookingRepo, chargeRepo, gateway)

  private val signupBody =
    """{
      "email":    "locador@charge.com",
      "password": "securepassword123",
      "name":     "Locador Charge",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@charge.com","password":"securepassword123"}"""

  private case class Auth(token: String, id: String)

  private def signupAndLogin(ctx: Ctx): IO[Auth] =
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
      token      = json.hcursor.downField("token").as[String].toOption.get
      id         = json.hcursor.downField("id").as[String].toOption.get
    yield Auth(token, id)

  private def getSlug(ctx: Ctx, providerId: String): IO[String] =
    val pid = ProviderId.fromString(providerId).toOption.get
    ctx.providerRepo.findById(pid).map(_.get.storefrontSlug.value)

  private def makePremiumWithWallet(ctx: Ctx, auth: Auth): IO[Unit] =
    val pid = ProviderId.fromString(auth.id).toOption.get
    ctx.providerRepo.findById(pid).flatMap {
      case Some(p) =>
        val updated = p.withPlanTier(PlanTier.Premium).withWalletId("wlt_test123")
        ctx.providerRepo.update(updated).void
      case None => IO.unit
    }

  private val itemBody =
    """{
      "name":                 "Cadeira de Festa",
      "description":          "Cadeira dobrável",
      "dailyRate":            50.00,
      "stock":                10,
      "attendantRequirement": "Optional",
      "imageUrls":            ["https://example.com/img.jpg"]
    }"""

  private def createBooking(ctx: Ctx, slug: String): IO[String] =
    val itemReq = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
      .withEntity(itemBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    for
      auth      <- signupAndLogin(ctx)
      _         <- makePremiumWithWallet(ctx, auth)
      itemResp  <- ctx.allRoutes.orNotFound(
                     itemReq.withHeaders(
                       Header.Raw(ci"Content-Type", "application/json"),
                       Header.Raw(ci"Authorization", s"Bearer ${auth.token}")
                     )
                   )
      itemBody  <- itemResp.as[String]
      itemId     = parse(itemBody).toOption.get.hcursor.downField("itemId").as[String].toOption.get
      bookBody   = s"""{
                     "items": [{"itemId":"$itemId","quantity":1}],
                     "date": "2026-09-01",
                     "deliveryAddress": {
                       "street":"Rua A","number":"1","neighborhood":"Centro",
                       "city":"São Paulo","state":"SP","cep":"01000-000","complement":null
                     },
                     "customer": {"name":"Maria","email":"maria@test.com","phone":null}
                   }"""
      bookResp  <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/storefront/$slug/bookings"))
                       .withEntity(bookBody)
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      bookRBody <- bookResp.as[String]
    yield parse(bookRBody).toOption.get.hcursor.downField("bookingId").as[String].toOption.get

  private def postCharge(ctx: Ctx, slug: String, bookingId: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/storefront/$slug/bookings/$bookingId/charge"))
    )

  // ── POST /storefront/:slug/bookings/:id/charge ───────────────────────────

  test("POST charge returns 201 + paymentUrl for Premium provider with walletId") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      _         <- makePremiumWithWallet(ctx, auth)
      slug      <- getSlug(ctx, auth.id)
      bookingId <- createBooking(ctx, slug)
      resp      <- postCharge(ctx, slug, bookingId)
      body      <- resp.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Created, s"body: $body")
      assert(json.hcursor.downField("paymentUrl").as[String].toOption.exists(_.nonEmpty), s"body: $body")
  }

  test("POST charge is idempotent: second call returns 200 + same paymentUrl") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      _         <- makePremiumWithWallet(ctx, auth)
      slug      <- getSlug(ctx, auth.id)
      bookingId <- createBooking(ctx, slug)
      first     <- postCharge(ctx, slug, bookingId)
      firstBody <- first.as[String]
      firstUrl   = parse(firstBody).toOption.get.hcursor.downField("paymentUrl").as[String].toOption.get
      second    <- postCharge(ctx, slug, bookingId)
      secondBody <- second.as[String]
      secondUrl  = parse(secondBody).toOption.get.hcursor.downField("paymentUrl").as[String].toOption.get
    yield
      assertEquals(first.status,  Status.Created)
      assertEquals(second.status, Status.Ok)
      assertEquals(secondUrl, firstUrl)
  }

  test("POST charge returns 403 for Freemium provider") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      // provider stays Freemium by default (no makePremiumWithWallet)
      slug      <- getSlug(ctx, auth.id)
      // create a booking directly in repo (bypassing online-payment check)
      provider  <- ctx.providerRepo.findById(ProviderId.fromString(auth.id).toOption.get).map(_.get)
      customer   = Customer.create(
                     id    = CustomerId.generate,
                     email = Email.fromString("x@x.com").toOption.get,
                     name  = "X"
                   ).toOption.get
      booking    = Booking.create(
                     id          = BookingId.generate,
                     providerId  = provider.id,
                     customerId  = customer.id,
                     items       = List(BookedIndividualItem(ItemId.generate, 1)),
                     startDate   = date,
                     endDate     = date,
                     totalAmount = totalMoney
                   ).toOption.get
      _         <- ctx.bookingRepo.create(booking)
      resp      <- postCharge(ctx, slug, booking.id.value)
    yield assertEquals(resp.status, Status.Forbidden, s"Expected 403 for Freemium")
  }

  test("POST charge returns 404 for unknown slug") {
    for
      ctx  <- makeCtx
      resp <- postCharge(ctx, "no-such-slug-000000", BookingId.generate.value)
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("POST charge returns 404 for unknown bookingId") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      _    <- makePremiumWithWallet(ctx, auth)
      slug <- getSlug(ctx, auth.id)
      resp <- postCharge(ctx, slug, BookingId.generate.value)
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("POST charge does NOT change BookingStatus") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      _         <- makePremiumWithWallet(ctx, auth)
      slug      <- getSlug(ctx, auth.id)
      bookingId <- createBooking(ctx, slug)
      _         <- postCharge(ctx, slug, bookingId)
      stored    <- ctx.bookingRepo.findById(BookingId.fromString(bookingId).toOption.get)
    yield assertEquals(stored.get.status, BookingStatus.Pending)
  }

  test("POST charge is public — no Authorization header required") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      _         <- makePremiumWithWallet(ctx, auth)
      slug      <- getSlug(ctx, auth.id)
      bookingId <- createBooking(ctx, slug)
      resp      <- postCharge(ctx, slug, bookingId)
    yield assert(
      resp.status != Status.Unauthorized,
      s"Expected public access (not 401) but got ${resp.status}"
    )
  }

  // ── OpenAPI ──────────────────────────────────────────────────────────────

  test("StorefrontChargeRoutes.allEndpoints is non-empty") {
    assert(StorefrontChargeRoutes.allEndpoints.nonEmpty)
  }

  test("StorefrontChargeRoutes endpoint path includes api/v1/storefront/bookings/charge") {
    val desc = StorefrontChargeRoutes.allEndpoints.map(_.show).mkString
    assert(desc.contains("storefront"), s"Expected 'storefront' in endpoint: $desc")
    assert(desc.contains("charge"),     s"Expected 'charge' in endpoint: $desc")
  }

  test("OpenAPI YAML includes charge endpoint") {
    val yaml = OpenAPIDocsInterpreter()
      .toOpenAPI(StorefrontChargeRoutes.allEndpoints, "Test", "1.0")
      .toYaml
    assert(yaml.contains("charge"), s"Expected 'charge' in OpenAPI spec")
    assert(yaml.contains("paymentUrl"), s"Expected 'paymentUrl' field in OpenAPI spec")
  }
