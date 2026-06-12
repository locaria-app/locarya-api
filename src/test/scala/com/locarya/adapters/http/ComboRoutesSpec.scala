package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, ComboServiceImpl, ItemServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemImageRepository,
  InMemoryItemRepository,
  InMemoryProviderRepository
}
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

class ComboRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-combos"

  private case class Ctx(
    authRoutes:  HttpRoutes[IO],
    itemRoutes:  HttpRoutes[IO],
    comboRoutes: HttpRoutes[IO],
    itemRepo:    InMemoryItemRepository[IO],
    comboRepo:   InMemoryComboRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> itemRoutes <+> comboRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      providerSvc   = ProviderServiceImpl[IO](providerRepo)
      authSvc       = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      itemSvc       = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)
      comboSvc      = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)
      auth          = AuthRoutes.routes[IO](providerSvc, authSvc)
      items         = ItemRoutes.routes[IO](itemSvc, testJwtSecret)
      combos        = ComboRoutes.routes[IO](comboSvc, testJwtSecret)
    yield Ctx(auth, items, combos, itemRepo, comboRepo, bookingRepo)

  private val signupBody =
    """{
      "email":    "locador@combos.com",
      "password": "securepassword123",
      "name":     "Locador Combo",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@combos.com","password":"securepassword123"}"""

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
      "description":          "Cadeira dobrável",
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

  private def validComboBody(itemId: String) =
    s"""{
      "name":        "Kit Festa",
      "description": "Pacote completo",
      "dailyRate":   300.00,
      "itemCompositions": [{"itemId":"$itemId","quantity":2}]
    }"""

  private def createCombo(ctx: Ctx, token: String, itemId: String): IO[String] =
    val req = Request[IO](Method.POST, uri"/dashboard/combos")
      .withEntity(validComboBody(itemId))
      .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    for
      resp <- ctx.allRoutes.orNotFound(req)
      body <- resp.as[String]
    yield parse(body).toOption.get.hcursor.downField("comboId").as[String].toOption.get

  // ── POST /dashboard/combos ───────────────────────────────────────────────────

  test("POST /dashboard/combos without token returns 401") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      request   = Request[IO](Method.POST, uri"/dashboard/combos")
                    .withEntity(validComboBody(itemId))
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("POST /dashboard/combos with valid token and body returns 201 with comboId") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      request   = Request[IO](Method.POST, uri"/dashboard/combos")
                    .withEntity(validComboBody(itemId))
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Created)
      assert(json.hcursor.downField("comboId").focus.isDefined, s"Expected comboId in response: $body")
  }

  test("POST /dashboard/combos with nonexistent itemId returns 400") {
    val fakeItemId = ItemId.generate.value
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      request   = Request[IO](Method.POST, uri"/dashboard/combos")
                    .withEntity(validComboBody(fakeItemId))
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /dashboard/combos with a combo UUID as itemId returns 400") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      comboId  <- createCombo(ctx, auth.token, itemId)
      // Use the combo's ID as if it were an item
      request   = Request[IO](Method.POST, uri"/dashboard/combos")
                    .withEntity(validComboBody(comboId))
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("POST /dashboard/combos with empty name returns 400") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      badBody   = s"""{"name":"","description":"Desc","dailyRate":100.0,"itemCompositions":[{"itemId":"$itemId","quantity":1}]}"""
      request   = Request[IO](Method.POST, uri"/dashboard/combos")
                    .withEntity(badBody)
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.BadRequest)
  }

  // ── GET /dashboard/combos/:id ────────────────────────────────────────────────

  test("GET /dashboard/combos/:id without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.GET, uri"/dashboard/combos/some-id")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("GET /dashboard/combos/:id returns 200 with combo details including itemCompositions") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      comboId  <- createCombo(ctx, auth.token, itemId)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json.hcursor.downField("comboId").as[String].toOption, Some(comboId))
      assert(json.hcursor.downField("itemCompositions").focus.isDefined, s"Expected itemCompositions in response: $body")
  }

  test("GET /dashboard/combos/:id for nonexistent combo returns 404") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      fakeId    = ComboId.generate.value
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/dashboard/combos/$fakeId"))
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  // ── PUT /dashboard/combos/:id ────────────────────────────────────────────────

  test("PUT /dashboard/combos/:id without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.PUT, uri"/dashboard/combos/some-id")
                    .withEntity("""{"name":"x","description":"d","dailyRate":100}""")
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("PUT /dashboard/combos/:id updates name and price and returns 200") {
    val updateBody = """{"name":"Kit Premium","description":"Novo desc","dailyRate":500.00}"""
    for
      ctx        <- makeCtx
      auth       <- signupAndLogin(ctx)
      itemId     <- createItem(ctx, auth.token)
      comboId    <- createCombo(ctx, auth.token, itemId)
      request     = Request[IO](Method.PUT, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                      .withEntity(updateBody)
                      .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response   <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Ok)
  }

  test("PUT /dashboard/combos/:id with itemCompositions and no bookings returns 200") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      itemId    <- createItem(ctx, auth.token)
      comboId   <- createCombo(ctx, auth.token, itemId)
      updateBody = s"""{"name":"Kit","description":"D","dailyRate":300.00,"itemCompositions":[{"itemId":"$itemId","quantity":3}]}"""
      request    = Request[IO](Method.PUT, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                     .withEntity(updateBody)
                     .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response  <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Ok)
  }

  test("PUT /dashboard/combos/:id with itemCompositions when bookings exist returns 409") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      itemId    <- createItem(ctx, auth.token)
      comboId   <- createCombo(ctx, auth.token, itemId)
      cid        = ComboId.fromString(comboId).toOption.get
      pid        = ProviderId.fromString(auth.id).toOption.get
      booking    = Booking.create(
                     id          = BookingId.generate,
                     providerId  = pid,
                     customerId  = CustomerId.generate,
                     items       = List(BookedCombo(cid, 1)),
                     startDate   = java.time.LocalDate.of(2026, 10, 1),
                     endDate     = java.time.LocalDate.of(2026, 10, 3),
                     totalAmount = Money.fromAmount(BigDecimal("300")).toOption.get
                   ).toOption.get
      _         <- ctx.bookingRepo.create(booking)
      updateBody = s"""{"name":"Kit","description":"D","dailyRate":300.00,"itemCompositions":[{"itemId":"$itemId","quantity":5}]}"""
      request    = Request[IO](Method.PUT, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                     .withEntity(updateBody)
                     .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
      response  <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Conflict)
  }

  test("PUT /dashboard/combos/:id by another provider returns 403") {
    for
      ctx         <- makeCtx
      auth1       <- signupAndLogin(ctx)
      itemId      <- createItem(ctx, auth1.token)
      comboId     <- createCombo(ctx, auth1.token, itemId)
      // Second provider
      providerRepo2 <- InMemoryProviderRepository.make[IO]
      providerSvc2   = ProviderServiceImpl[IO](providerRepo2)
      authSvc2       = AuthServiceImpl[IO](providerRepo2, testJwtSecret)
      auth2Routes    = AuthRoutes.routes[IO](providerSvc2, authSvc2)
      signup2Body    = """{
                           "email":"outro@combos.com","password":"securepassword123",
                           "name":"Outro","city":"SP","state":"SP","cpf":"123.456.789-09"
                         }"""
      login2Body     = """{"email":"outro@combos.com","password":"securepassword123"}"""
      _             <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/auth/signup")
                           .withEntity(signup2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginResp2    <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/auth/login")
                           .withEntity(login2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginBody2    <- loginResp2.as[String]
      token2         = parse(loginBody2).toOption.get.hcursor.downField("token").as[String].toOption.get
      updateBody     = """{"name":"Hack","description":"","dailyRate":100.00}"""
      response      <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.PUT, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                           .withEntity(updateBody)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token2))
                       )
    yield assertEquals(response.status, Status.Forbidden)
  }

  // ── DELETE /dashboard/combos/:id ─────────────────────────────────────────────

  test("DELETE /dashboard/combos/:id without token returns 401") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.DELETE, uri"/dashboard/combos/some-id")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.Unauthorized)
  }

  test("DELETE /dashboard/combos/:id soft-deletes and returns 200") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      comboId  <- createCombo(ctx, auth.token, itemId)
      request   = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
      stored   <- ctx.comboRepo.findById(ComboId.fromString(comboId).toOption.get)
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(stored.map(_.isActive), Some(false))
  }

  test("DELETE /dashboard/combos/:id for nonexistent combo returns 404") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      fakeId    = ComboId.generate.value
      request   = Request[IO](Method.DELETE, Uri.unsafeFromString(s"/dashboard/combos/$fakeId"))
                    .withHeaders(authHeader(auth.token))
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  test("DELETE /dashboard/combos/:id by another provider returns 403") {
    for
      ctx         <- makeCtx
      auth1       <- signupAndLogin(ctx)
      itemId      <- createItem(ctx, auth1.token)
      comboId     <- createCombo(ctx, auth1.token, itemId)
      providerRepo2 <- InMemoryProviderRepository.make[IO]
      providerSvc2   = ProviderServiceImpl[IO](providerRepo2)
      authSvc2       = AuthServiceImpl[IO](providerRepo2, testJwtSecret)
      auth2Routes    = AuthRoutes.routes[IO](providerSvc2, authSvc2)
      signup2Body    = """{
                           "email":"outro2@combos.com","password":"securepassword123",
                           "name":"Outro2","city":"SP","state":"SP","cpf":"987.654.321-00"
                         }"""
      login2Body     = """{"email":"outro2@combos.com","password":"securepassword123"}"""
      _             <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/auth/signup")
                           .withEntity(signup2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginResp2    <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.POST, uri"/auth/login")
                           .withEntity(login2Body)
                           .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                       )
      loginBody2    <- loginResp2.as[String]
      token2         = parse(loginBody2).toOption.get.hcursor.downField("token").as[String].toOption.get
      response      <- (auth2Routes <+> ctx.comboRoutes).orNotFound(
                         Request[IO](Method.DELETE, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                           .withHeaders(authHeader(token2))
                       )
    yield assertEquals(response.status, Status.Forbidden)
  }
