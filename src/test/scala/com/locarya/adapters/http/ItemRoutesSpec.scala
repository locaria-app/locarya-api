package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, ItemServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{InMemoryBookingRepository, InMemoryItemImageRepository, InMemoryItemRepository, InMemoryProviderRepository}
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

class ItemRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-items"

  private case class Ctx(
    authRoutes:  HttpRoutes[IO],
    itemRoutes:  HttpRoutes[IO],
    itemRepo:    InMemoryItemRepository[IO],
    imageRepo:   InMemoryItemImageRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> itemRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      providerSvc   = ProviderServiceImpl[IO](providerRepo)
      authSvc       = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      itemSvc       = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)
      auth          = AuthRoutes.routes[IO](providerSvc, authSvc)
      items         = ItemRoutes.routes[IO](itemSvc, testJwtSecret)
    yield Ctx(auth, items, itemRepo, imageRepo, bookingRepo)

  private val signupBody =
    """{
      "email":    "locador@example.com",
      "password": "Securepass123",
      "name":     "Locador Teste",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@example.com","password":"Securepass123"}"""

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

  private val validItemBody =
    """{
      "name":            "Cama Elástica Grande",
      "description":     "Cama elástica para festas",
      "dailyRate":       150.00,
      "stock":           2,
      "requiresMonitor": true,
      "imageUrls":       ["https://example.com/img1.jpg","https://example.com/img2.jpg"]
    }"""

  private def authHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private def createItem(ctx: Ctx, token: String): IO[String] =
    val createReq = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
      .withEntity(validItemBody)
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    for
      resp <- ctx.allRoutes.orNotFound(createReq)
      body <- resp.as[String]
    yield parse(body).toOption.get.hcursor.downField("itemId").as[String].toOption.get

  // ── GET /api/v1/dashboard/items ──────────────────────────────────────────────

  test("GET /api/v1/dashboard/items without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.GET, uri"/api/v1/dashboard/items")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("GET /api/v1/dashboard/items with valid token returns 200 and JSON array") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.GET, uri"/api/v1/dashboard/items")
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assert(json.isArray, "Expected JSON array response")
  }

  // ── POST /api/v1/dashboard/items ─────────────────────────────────────────────

  test("POST /api/v1/dashboard/items without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
                    .withEntity(validItemBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("POST /api/v1/dashboard/items with valid token and body returns 201 with itemId") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
                    .withEntity(validItemBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Created)
      assert(json.hcursor.downField("itemId").focus.isDefined, s"Expected itemId in response: $body")
  }

  test("POST /api/v1/dashboard/items with stock = 0 returns 400") {
    val badBody = validItemBody.replace("\"stock\":           2", "\"stock\":           0")
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
                    .withEntity(badBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /api/v1/dashboard/items with no imageUrls returns 400") {
    val badBody = validItemBody.replace(
      """"imageUrls":       ["https://example.com/img1.jpg","https://example.com/img2.jpg"]""",
      """"imageUrls":       []"""
    )
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/items")
                    .withEntity(badBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  // ── PUT /api/v1/dashboard/items/:id ──────────────────────────────────────────

  test("PUT /api/v1/dashboard/items/:id without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.PUT, uri"/api/v1/dashboard/items/some-id")
                    .withEntity(validItemBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("PUT /api/v1/dashboard/items/:id updates item and returns 200") {
    val updatedBody =
      """{
        "name":            "Cama Elástica Atualizada",
        "description":     "Nova descrição",
        "dailyRate":       200.00,
        "stock":           4,
        "requiresMonitor": false,
        "imageUrls":       ["https://example.com/new.jpg"]
      }"""
    for
      ctx        <- makeCtx
      auth       <- signupAndLogin(ctx)
      itemId     <- createItem(ctx, auth.token)
      updateReq   = Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                      .withEntity(updatedBody)
                      .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      updateResp <- ctx.allRoutes.orNotFound(updateReq)
    yield assertEquals(updateResp.status, Status.Ok)
  }

  test("PUT /api/v1/dashboard/items/:id by another provider returns 403") {
    for
      ctx          <- makeCtx
      auth1        <- signupAndLogin(ctx)
      // Second provider in its own repo
      providerRepo2 <- InMemoryProviderRepository.make[IO]
      providerSvc2   = ProviderServiceImpl[IO](providerRepo2)
      authSvc2       = AuthServiceImpl[IO](providerRepo2, testJwtSecret)
      auth2Routes    = AuthRoutes.routes[IO](providerSvc2, authSvc2)
      signup2Body    = """{
                           "email":"outro@example.com","password":"Securepass123",
                           "name":"Outro","city":"SP","state":"SP","cpf":"123.456.789-09"
                         }"""
      login2Body     = """{"email":"outro@example.com","password":"Securepass123"}"""
      _             <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/api/v1/auth/signup")
                           .withEntity(signup2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginResp2    <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/api/v1/auth/login")
                           .withEntity(login2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginBody2    <- loginResp2.as[String]
      token2         = parse(loginBody2).toOption.get.hcursor.downField("token").as[String].toOption.get
      // Create item as provider1
      itemId        <- createItem(ctx, auth1.token)
      // Try to update as provider2
      updateResp    <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                           .withEntity(validItemBody)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token2))
                       )
    yield assertEquals(updateResp.status, Status.Forbidden)
  }

  // ── DELETE /api/v1/dashboard/items/:id ───────────────────────────────────────

  test("DELETE /api/v1/dashboard/items/:id without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.DELETE, uri"/api/v1/dashboard/items/some-id")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("DELETE /api/v1/dashboard/items/:id soft-deletes item and returns 200") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      delReq    = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                    .withHeaders(authHeader(auth.token))
      delResp  <- ctx.allRoutes.orNotFound(delReq)
    yield assertEquals(delResp.status, Status.Ok)
  }

  test("DELETE /api/v1/dashboard/items/:id when item has bookings returns 409") {
    for
      ctx     <- makeCtx
      auth    <- signupAndLogin(ctx)
      itemId  <- createItem(ctx, auth.token)
      iid      = ItemId.fromString(itemId).toOption.get
      booking  = Booking.create(
                   id = BookingId.generate,
                   providerId = ProviderId.generate,
                   customerId = CustomerId.generate,
                   items = List(BookedIndividualItem(iid, 1)),
                   startDate = java.time.LocalDate.of(2026, 9, 1),
                   endDate = java.time.LocalDate.of(2026, 9, 3),
                   totalAmount = Money.fromAmount(BigDecimal("100")).toOption.get
                 ).toOption.get
      _       <- ctx.bookingRepo.create(booking)
      delReq   = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                   .withHeaders(authHeader(auth.token))
      delResp <- ctx.allRoutes.orNotFound(delReq)
    yield assertEquals(delResp.status, Status.Conflict)
  }

  // ── POST /api/v1/dashboard/items/:id/activate ────────────────────────────────

  test("POST /api/v1/dashboard/items/:id/activate without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.POST, uri"/api/v1/dashboard/items/some-id/activate")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("POST /api/v1/dashboard/items/:id/activate reactivates item and returns 200") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      delReq    = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                    .withHeaders(authHeader(auth.token))
      _        <- ctx.allRoutes.orNotFound(delReq)
      actReq    = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId/activate"))
                    .withHeaders(authHeader(auth.token))
      actResp  <- ctx.allRoutes.orNotFound(actReq)
      getResp  <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.GET, uri"/api/v1/dashboard/items").withHeaders(authHeader(auth.token))
                  )
      getBody  <- getResp.as[String]
      json      = parse(getBody).toOption.get
    yield
      assertEquals(actResp.status, Status.Ok)
      val items = json.asArray.getOrElse(Vector.empty)
      assert(items.exists(_.hcursor.downField("itemId").as[String].toOption.contains(itemId)),
        s"Reactivated item should appear in list: $getBody")
  }

  test("POST /api/v1/dashboard/items/:id/activate by another provider returns 403") {
    for
      ctx           <- makeCtx
      auth1         <- signupAndLogin(ctx)
      providerRepo2 <- InMemoryProviderRepository.make[IO]
      providerSvc2   = ProviderServiceImpl[IO](providerRepo2)
      authSvc2       = AuthServiceImpl[IO](providerRepo2, testJwtSecret)
      auth2Routes    = AuthRoutes.routes[IO](providerSvc2, authSvc2)
      signup2Body    = """{
                           "email":"outro2@example.com","password":"Securepass123",
                           "name":"Outro","city":"SP","state":"SP","cpf":"123.456.789-09"
                         }"""
      login2Body     = """{"email":"outro2@example.com","password":"Securepass123"}"""
      _             <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/api/v1/auth/signup")
                           .withEntity(signup2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginResp2    <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/api/v1/auth/login")
                           .withEntity(login2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginBody2    <- loginResp2.as[String]
      token2         = parse(loginBody2).toOption.get.hcursor.downField("token").as[String].toOption.get
      itemId        <- createItem(ctx, auth1.token)
      actResp       <- (auth2Routes <+> ctx.itemRoutes).orNotFound(
                         Request[IO](Method.POST, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId/activate"))
                           .withHeaders(authHeader(token2))
                       )
    yield assertEquals(actResp.status, Status.Forbidden)
  }

  // ── GET /api/v1/dashboard/items list behaviour ────────────────────────────────

  test("GET /api/v1/dashboard/items returns created item in list") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      getReq    = Request[IO](Method.GET, uri"/api/v1/dashboard/items").withHeaders(authHeader(auth.token))
      getResp  <- ctx.allRoutes.orNotFound(getReq)
      getBody  <- getResp.as[String]
      json      = parse(getBody).toOption.get
    yield
      assertEquals(getResp.status, Status.Ok)
      val items = json.asArray.getOrElse(Vector.empty)
      assert(items.exists(_.hcursor.downField("itemId").as[String].toOption.contains(itemId)),
        s"Expected item $itemId in response: $getBody")
  }

  test("GET /api/v1/dashboard/items still returns deactivated items, marked isActive=false") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      delReq    = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/items/$itemId"))
                    .withHeaders(authHeader(auth.token))
      _        <- ctx.allRoutes.orNotFound(delReq)
      getResp  <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.GET, uri"/api/v1/dashboard/items").withHeaders(authHeader(auth.token))
                  )
      getBody  <- getResp.as[String]
      json      = parse(getBody).toOption.get
    yield
      val items = json.asArray.getOrElse(Vector.empty)
      val found = items.find(_.hcursor.downField("itemId").as[String].toOption.contains(itemId))
      assert(found.isDefined, s"Deactivated item should still appear in list: $getBody")
      assertEquals(found.flatMap(_.hcursor.downField("isActive").as[Boolean].toOption), Some(false))
  }

  // ── Old path (without /api/v1 prefix) must not match ─────────────────────────

  test("GET /dashboard/items (without /api/v1 prefix) returns 404") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.GET, uri"/dashboard/items")
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  // ── imageUrls in list response ────────────────────────────────────────────────

  test("GET /api/v1/dashboard/items returns imageUrls per item") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      _        <- createItem(ctx, auth.token)
      getReq    = Request[IO](Method.GET, uri"/api/v1/dashboard/items").withHeaders(authHeader(auth.token))
      getResp  <- ctx.allRoutes.orNotFound(getReq)
      getBody  <- getResp.as[String]
      json      = parse(getBody).toOption.get
    yield
      val items = json.asArray.getOrElse(Vector.empty)
      assert(items.nonEmpty, "Expected at least one item in response")
      items.foreach { item =>
        val imageUrls = item.hcursor.downField("imageUrls").as[List[String]].toOption
        assert(imageUrls.isDefined, s"Expected imageUrls field in item: $item")
        assert(imageUrls.get.nonEmpty, s"Expected non-empty imageUrls for created item: $item")
      }
  }

  test("GET /api/v1/dashboard/items — imageUrls contains exactly the created URLs") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      _        <- createItem(ctx, auth.token)
      getReq    = Request[IO](Method.GET, uri"/api/v1/dashboard/items").withHeaders(authHeader(auth.token))
      getResp  <- ctx.allRoutes.orNotFound(getReq)
      getBody  <- getResp.as[String]
      json      = parse(getBody).toOption.get
    yield
      val items = json.asArray.getOrElse(Vector.empty)
      items.foreach { item =>
        val imageUrls = item.hcursor.downField("imageUrls").as[List[String]].toOption.getOrElse(Nil)
        assertEquals(imageUrls.toSet, Set("https://example.com/img1.jpg", "https://example.com/img2.jpg"))
      }
  }
