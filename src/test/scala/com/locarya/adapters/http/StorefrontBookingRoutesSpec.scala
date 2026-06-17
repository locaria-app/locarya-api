package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, AvailabilityServiceImpl, BookingServiceImpl, ItemServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryCustomerRepository,
  InMemoryItemImageRepository,
  InMemoryItemRepository,
  InMemoryProviderRepository
}
import io.circe.parser.parse
import java.time.LocalDate
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class StorefrontBookingRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-booking"
  private val date          = LocalDate.of(2026, 9, 1)

  private case class Ctx(
    authRoutes:    HttpRoutes[IO],
    itemRoutes:    HttpRoutes[IO],
    bookingRoutes: HttpRoutes[IO],
    providerRepo:  InMemoryProviderRepository[IO],
    itemRepo:      InMemoryItemRepository[IO],
    bookingRepo:   InMemoryBookingRepository[IO],
    customerRepo:  InMemoryCustomerRepository[IO]
  ):
    // Public booking routes must precede the auth-gated item routes: the auth middleware
    // 401s any tokenless request regardless of path, which would otherwise shadow the
    // public POST /storefront/:slug/bookings.
    def allRoutes: HttpRoutes[IO] = authRoutes <+> bookingRoutes <+> itemRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      customerRepo <- InMemoryCustomerRepository.make[IO]
      providerSvc   = ProviderServiceImpl[IO](providerRepo)
      authSvc       = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      itemSvc       = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)
      availSvc      = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      bookingSvc    = BookingServiceImpl[IO](providerRepo, customerRepo, bookingRepo, itemRepo, comboRepo, availSvc)
      auth          = AuthRoutes.routes[IO](providerSvc, authSvc)
      items         = ItemRoutes.routes[IO](itemSvc, testJwtSecret)
      bookings      = StorefrontBookingRoutes.routes[IO](bookingSvc)
    yield Ctx(auth, items, bookings, providerRepo, itemRepo, bookingRepo, customerRepo)

  private val signupBody =
    """{
      "email":    "locador@booking.com",
      "password": "securepassword123",
      "name":     "Locador Booking",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@booking.com","password":"securepassword123"}"""

  private case class Auth(token: String, id: String)

  private def signupAndLogin(ctx: Ctx): IO[Auth] =
    val signupReq = Request[IO](Method.POST, uri"/auth/signup")
      .withEntity(signupBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    val loginReq = Request[IO](Method.POST, uri"/auth/login")
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

  private def authHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private val validItemBody =
    """{
      "name":                 "Cadeira de Festa",
      "description":          "Cadeira dobrável para eventos",
      "dailyRate":            50.00,
      "stock":                20,
      "attendantRequirement": "Optional",
      "imageUrls":            ["https://example.com/cadeira.jpg"]
    }"""

  private def createItem(ctx: Ctx, token: String): IO[String] =
    val req = Request[IO](Method.POST, uri"/dashboard/items")
      .withEntity(validItemBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    for
      resp <- ctx.allRoutes.orNotFound(req)
      body <- resp.as[String]
    yield parse(body).toOption.get.hcursor.downField("itemId").as[String].toOption.get

  private def getSlug(ctx: Ctx, providerId: String): IO[String] =
    val pid = ProviderId.fromString(providerId).toOption.get
    ctx.providerRepo.findById(pid).map(_.get.storefrontSlug.value)

  private def bookingBody(itemId: String, quantity: Int = 2): String =
    s"""{
      "items": [{"itemId":"$itemId","quantity":$quantity}],
      "date": "2026-09-01",
      "deliveryAddress": {
        "street":"Rua das Festas","number":"100","neighborhood":"Centro",
        "city":"São Paulo","state":"SP","cep":"01000-000","complement":null
      },
      "customer": {"name":"Maria Festa","email":"maria@cliente.com","phone":"11999990000"}
    }"""

  private def postBooking(ctx: Ctx, slug: String, body: String, headers: Header.ToRaw*): IO[Response[IO]] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString(s"/storefront/$slug/bookings"))
      .withEntity(body)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
    ctx.allRoutes.orNotFound(if headers.isEmpty then base else base.putHeaders(headers*))

  // ── POST /storefront/:slug/bookings ──────────────────────────────────────────

  test("POST /storefront/:slug/bookings returns 201 with bookingId, status, totalAmount, confirmationMessage") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      slug     <- getSlug(ctx, auth.id)
      response <- postBooking(ctx, slug, bookingBody(itemId))
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Created)
      assert(json.hcursor.downField("bookingId").focus.isDefined, body)
      assertEquals(json.hcursor.downField("status").as[String].toOption, Some("Pending"))
      assertEquals(json.hcursor.downField("totalAmount").as[BigDecimal].toOption, Some(BigDecimal("100.00")))
      assert(json.hcursor.downField("confirmationMessage").focus.isDefined, body)
  }

  test("POST booking persists the booking with Pending status (round-trip)") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      slug     <- getSlug(ctx, auth.id)
      response <- postBooking(ctx, slug, bookingBody(itemId))
      body     <- response.as[String]
      bookingId = parse(body).toOption.get.hcursor.downField("bookingId").as[String].toOption.get
      stored   <- ctx.bookingRepo.findById(BookingId.fromString(bookingId).toOption.get)
    yield
      assert(stored.isDefined, "Expected the booking to be persisted")
      assertEquals(stored.get.status, BookingStatus.Pending)
      assertEquals(stored.get.createdBy, BookingCreator.Customer)
  }

  test("POST booking is public — no Authorization header required") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      slug     <- getSlug(ctx, auth.id)
      response <- postBooking(ctx, slug, bookingBody(itemId))
    yield assert(
      response.status != Status.Unauthorized,
      s"Expected public access (not 401) but got ${response.status}"
    )
  }

  test("POST booking returns 409 naming the unavailable item when stock is exhausted") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      slug     <- getSlug(ctx, auth.id)
      item     <- ctx.itemRepo.findById(ItemId.fromString(itemId).toOption.get).map(_.get)
      // a Confirmed booking on the same date that exhausts the item's stock (20)
      blocker   = Booking.create(
                    id          = BookingId.generate,
                    providerId  = item.providerId,
                    customerId  = CustomerId.generate,
                    items       = List(BookedIndividualItem(item.id, item.stock)),
                    startDate   = date,
                    endDate     = date,
                    totalAmount = item.dailyRate,
                    status      = BookingStatus.Confirmed
                  ).toOption.get
      _        <- ctx.bookingRepo.create(blocker)
      response <- postBooking(ctx, slug, bookingBody(itemId, quantity = 1))
      body     <- response.as[String]
    yield
      assertEquals(response.status, Status.Conflict)
      assert(body.contains(itemId), s"Expected 409 body to name the unavailable item: $body")
  }

  test("POST booking returns 404 for an unknown storefront slug") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      response <- postBooking(ctx, "no-such-slug", bookingBody(itemId))
    yield assertEquals(response.status, Status.NotFound)
  }
