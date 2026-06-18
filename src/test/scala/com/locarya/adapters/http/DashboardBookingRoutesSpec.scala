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

class DashboardBookingRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-dashboard"
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
      bookings      = DashboardBookingRoutes.routes[IO](bookingSvc, testJwtSecret)
    yield Ctx(auth, items, bookings, providerRepo, itemRepo, bookingRepo, customerRepo)

  private val signupBody =
    """{
      "email":    "locador@dashboard.com",
      "password": "securepassword123",
      "name":     "Locador Dashboard",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@dashboard.com","password":"securepassword123"}"""

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
      "stock":                10,
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

  private def bookingBody(itemId: String, date: String = "2026-09-01"): String =
    s"""{
      "items":           [{"itemId":"$itemId","quantity":1}],
      "date":            "$date",
      "deliveryAddress": {
        "street":"Rua das Festas","number":"100","neighborhood":"Centro",
        "city":"São Paulo","state":"SP","cep":"01000-000","complement":null
      },
      "customer":        {"name":"Maria Festa","email":"maria@cliente.com","phone":"11999990000"}
    }"""

  private def postDashboardBooking(ctx: Ctx, body: String, token: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.POST, uri"/dashboard/bookings")
        .withEntity(body)
        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    )

  private def getDashboardBookings(ctx: Ctx, token: String, query: String = ""): IO[Response[IO]] =
    val uri = Uri.unsafeFromString(s"/dashboard/bookings$query")
    ctx.allRoutes.orNotFound(
      Request[IO](Method.GET, uri)
        .withHeaders(authHeader(token))
    )

  // ── Auth guard ────────────────────────────────────────────────────────────

  test("POST /dashboard/bookings returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.POST, uri"/dashboard/bookings")
                  .withEntity(bookingBody("some-id"))
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
              )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  test("GET /dashboard/bookings returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(Request[IO](Method.GET, uri"/dashboard/bookings"))
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  // ── POST /dashboard/bookings ──────────────────────────────────────────────

  test("POST /dashboard/bookings returns 201 with bookingId and confirmed status") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      itemId <- createItem(ctx, auth.token)
      resp   <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      body   <- resp.as[String]
      json    = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Created)
      assert(json.hcursor.downField("bookingId").focus.isDefined, body)
      assertEquals(json.hcursor.downField("status").as[String].toOption, Some("confirmed"))
  }

  test("POST /dashboard/bookings persists booking with Confirmed status and createdBy=Provider") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      resp     <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      body     <- resp.as[String]
      bookingId = parse(body).toOption.get.hcursor.downField("bookingId").as[String].toOption.get
      stored   <- ctx.bookingRepo.findById(BookingId.fromString(bookingId).toOption.get)
    yield
      assert(stored.isDefined, "Expected booking to be persisted")
      assertEquals(stored.get.status, BookingStatus.Confirmed)
      assertEquals(stored.get.createdBy, BookingCreator.Provider)
  }

  test("POST /dashboard/bookings returns 409 when item is unavailable") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      item     <- ctx.itemRepo.findById(ItemId.fromString(itemId).toOption.get).map(_.get)
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
      resp     <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      body     <- resp.as[String]
    yield
      assertEquals(resp.status, Status.Conflict)
      assert(body.contains(itemId), s"Expected 409 body to name the unavailable item: $body")
  }

  test("POST /dashboard/bookings returns 400 for malformed request body") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.POST, uri"/dashboard/bookings")
                  .withEntity("""{"bad":"json"}""")
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
              )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  // ── GET /dashboard/bookings ───────────────────────────────────────────────

  test("GET /dashboard/bookings returns 200 with the list of provider bookings") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      itemId <- createItem(ctx, auth.token)
      _      <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      resp   <- getDashboardBookings(ctx, auth.token)
      body   <- resp.as[String]
      json    = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.asArray.map(_.size), Some(1))
  }

  test("GET /dashboard/bookings returns empty list when provider has no bookings") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- getDashboardBookings(ctx, auth.token)
      body <- resp.as[String]
      json  = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.asArray.map(_.size), Some(0))
  }

  test("GET /dashboard/bookings filters by ?status=confirmed") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      itemId <- createItem(ctx, auth.token)
      _      <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      confirmed <- getDashboardBookings(ctx, auth.token, "?status=confirmed")
      pending   <- getDashboardBookings(ctx, auth.token, "?status=pending")
      confBody  <- confirmed.as[String]
      pendBody  <- pending.as[String]
    yield
      assertEquals(confirmed.status, Status.Ok)
      assertEquals(parse(confBody).toOption.get.asArray.map(_.size), Some(1))
      assertEquals(parse(pendBody).toOption.get.asArray.map(_.size), Some(0))
  }

  // ── PUT /dashboard/bookings/:id/status ───────────────────────────────────

  private def putBookingStatus(ctx: Ctx, bookingId: String, body: String, token: String): IO[Response[IO]] =
    val uri = Uri.unsafeFromString(s"/dashboard/bookings/$bookingId/status")
    ctx.allRoutes.orNotFound(
      Request[IO](Method.PUT, uri)
        .withEntity(body)
        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    )

  private def createAndGetBookingId(ctx: Ctx, auth: Auth): IO[String] =
    for
      itemId <- createItem(ctx, auth.token)
      resp   <- postDashboardBooking(ctx, bookingBody(itemId), auth.token)
      body   <- resp.as[String]
    yield parse(body).toOption.get.hcursor.downField("bookingId").as[String].toOption.get

  test("PUT /dashboard/bookings/:id/status returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.PUT, uri"/dashboard/bookings/some-id/status")
                  .withEntity("""{"newStatus":"in-progress"}""")
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
              )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  test("PUT /dashboard/bookings/:id/status with valid transition returns 200 with updated status") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      bookingId <- createAndGetBookingId(ctx, auth)
      resp      <- putBookingStatus(ctx, bookingId, """{"newStatus":"in-progress"}""", auth.token)
      body      <- resp.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.hcursor.downField("status").as[String].toOption, Some("in-progress"))
      assertEquals(json.hcursor.downField("id").as[String].toOption, Some(bookingId))
  }

  test("PUT /dashboard/bookings/:id/status with invalid newStatus string returns 400") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      bookingId <- createAndGetBookingId(ctx, auth)
      resp      <- putBookingStatus(ctx, bookingId, """{"newStatus":"flying"}""", auth.token)
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("PUT /dashboard/bookings/:id/status with invalid transition returns 400") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      bookingId <- createAndGetBookingId(ctx, auth)
      // Confirmed → Pending is not a valid transition
      resp      <- putBookingStatus(ctx, bookingId, """{"newStatus":"pending"}""", auth.token)
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("PUT /dashboard/bookings/:id/status cancelling InProgress booking returns 400") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      bookingId <- createAndGetBookingId(ctx, auth)
      _         <- putBookingStatus(ctx, bookingId, """{"newStatus":"in-progress"}""", auth.token)
      resp      <- putBookingStatus(ctx, bookingId, """{"newStatus":"cancelled"}""", auth.token)
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("PUT /dashboard/bookings/:id/status on non-existent bookingId returns 404") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      bogus   = BookingId.generate.value
      resp   <- putBookingStatus(ctx, bogus, """{"newStatus":"in-progress"}""", auth.token)
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("PUT /dashboard/bookings/:id/status on another provider's booking returns 404") {
    for
      ctx       <- makeCtx
      auth1     <- signupAndLogin(ctx)
      // Register a second provider and log in as them
      _         <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/signup")
                       .withEntity("""{
                         "email":"other@dashboard.com","password":"otherpass123",
                         "name":"Other Locador","city":"Rio","state":"RJ","cnpj":"11.222.333/0001-81"
                       }""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      loginResp <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/login")
                       .withEntity("""{"email":"other@dashboard.com","password":"otherpass123"}""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      body2     <- loginResp.as[String]
      token2     = parse(body2).toOption.get.hcursor.downField("token").as[String].toOption.get
      // Provider 1 creates a booking
      bookingId <- createAndGetBookingId(ctx, auth1)
      // Provider 2 tries to transition it
      resp      <- putBookingStatus(ctx, bookingId, """{"newStatus":"in-progress"}""", token2)
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("GET /dashboard/bookings filters by ?dateFrom and ?dateTo") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      itemId    <- createItem(ctx, auth.token)
      _         <- postDashboardBooking(ctx, bookingBody(itemId, "2026-09-01"), auth.token)
      inRange   <- getDashboardBookings(ctx, auth.token, "?dateFrom=2026-08-31&dateTo=2026-09-02")
      outOfRange <- getDashboardBookings(ctx, auth.token, "?dateFrom=2026-09-02&dateTo=2026-09-03")
      inBody    <- inRange.as[String]
      outBody   <- outOfRange.as[String]
    yield
      assertEquals(parse(inBody).toOption.get.asArray.map(_.size), Some(1))
      assertEquals(parse(outBody).toOption.get.asArray.map(_.size), Some(0))
  }
