package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, ComboServiceImpl, ItemServiceImpl, ProviderServiceImpl, StorefrontServiceImpl}
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

class StorefrontRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-storefront"

  private case class Ctx(
    storefrontRoutes: HttpRoutes[IO],
    authRoutes:       HttpRoutes[IO],
    itemRoutes:       HttpRoutes[IO],
    comboRoutes:      HttpRoutes[IO],
    providerRepo:     InMemoryProviderRepository[IO]
  ):
    def allRoutes: HttpRoutes[IO] = authRoutes <+> itemRoutes <+> comboRoutes <+> storefrontRoutes

  private def makeCtx: IO[Ctx] =
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      itemRepo      <- InMemoryItemRepository.make[IO]
      imageRepo     <- InMemoryItemImageRepository.make[IO]
      comboRepo     <- InMemoryComboRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      providerSvc    = ProviderServiceImpl[IO](providerRepo)
      authSvc        = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      itemSvc        = ItemServiceImpl[IO](itemRepo, imageRepo, bookingRepo)
      comboSvc       = ComboServiceImpl[IO](comboRepo, itemRepo, bookingRepo)
      storefrontSvc  = StorefrontServiceImpl[IO](providerRepo, itemRepo, imageRepo, comboRepo)
      auth           = AuthRoutes.routes[IO](providerSvc, authSvc)
      items          = ItemRoutes.routes[IO](itemSvc, testJwtSecret)
      combos         = ComboRoutes.routes[IO](comboSvc, testJwtSecret)
      storefront     = StorefrontRoutes.routes[IO](storefrontSvc)
    yield Ctx(storefront, auth, items, combos, providerRepo)

  private val signupBody =
    """{
      "email":    "locador@storefront.com",
      "password": "securepassword123",
      "name":     "Locador Storefront",
      "city":     "São Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@storefront.com","password":"securepassword123"}"""

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

  private def validComboBody(itemId: String) =
    s"""{
      "name":        "Kit Festa",
      "description": "Pacote completo para festas",
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

  private def getSlug(ctx: Ctx, providerId: String): IO[String] =
    val pid = ProviderId.fromString(providerId).toOption.get
    ctx.providerRepo.findById(pid).map(_.get.storefrontSlug.value)

  // ── GET /storefront/:slug ────────────────────────────────────────────────────

  test("GET /storefront/:slug returns 200 with provider info and active catalog") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      _        <- createCombo(ctx, auth.token, itemId)
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assert(json.hcursor.downField("name").focus.isDefined, s"Expected name in response: $body")
      assert(json.hcursor.downField("items").focus.isDefined, s"Expected items in response: $body")
      assert(json.hcursor.downField("combos").focus.isDefined, s"Expected combos in response: $body")
  }

  test("GET /storefront/:slug response includes item fields and images") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      _        <- createItem(ctx, auth.token)
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
      items     = json.hcursor.downField("items").focus.get.asArray.get
    yield
      assertEquals(response.status, Status.Ok)
      assert(items.nonEmpty, s"Expected at least one item: $body")
      val item = items.head
      assert(item.hcursor.downField("id").focus.isDefined,                    s"Missing id: $body")
      assert(item.hcursor.downField("name").focus.isDefined,                  s"Missing name: $body")
      assert(item.hcursor.downField("description").focus.isDefined,           s"Missing description: $body")
      assert(item.hcursor.downField("price").focus.isDefined,                 s"Missing price: $body")
      assert(item.hcursor.downField("stockQuantity").focus.isDefined,         s"Missing stockQuantity: $body")
      assert(item.hcursor.downField("attendantRequirement").focus.isDefined,  s"Missing attendantRequirement: $body")
      val images = item.hcursor.downField("images").focus.get.asArray.get
      assert(images.nonEmpty, s"Expected images array to be non-empty: $body")
      assertEquals(images.head.hcursor.downField("isPrimary").as[Boolean].toOption, Some(true))
      assert(images.head.hcursor.downField("url").focus.isDefined,            s"Missing url in image: $body")
      assert(images.head.hcursor.downField("displayOrder").focus.isDefined,   s"Missing displayOrder in image: $body")
  }

  test("GET /storefront/:slug response includes combo fields and item compositions") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      _        <- createCombo(ctx, auth.token, itemId)
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
      combos    = json.hcursor.downField("combos").focus.get.asArray.get
    yield
      assertEquals(response.status, Status.Ok)
      assert(combos.nonEmpty, s"Expected at least one combo: $body")
      val combo = combos.head
      assert(combo.hcursor.downField("id").focus.isDefined,               s"Missing id: $body")
      assert(combo.hcursor.downField("name").focus.isDefined,             s"Missing name: $body")
      assert(combo.hcursor.downField("description").focus.isDefined,      s"Missing description: $body")
      assert(combo.hcursor.downField("price").focus.isDefined,            s"Missing price: $body")
      assert(combo.hcursor.downField("attendantRequirement").focus.isDefined, s"Missing attendantRequirement: $body")
      val compositions = combo.hcursor.downField("itemCompositions").focus.get.asArray.get
      assert(compositions.nonEmpty, s"Expected at least one item composition: $body")
      val comp = compositions.head
      assertEquals(comp.hcursor.downField("quantity").as[Int].toOption, Some(2))
      assert(comp.hcursor.downField("item").focus.isDefined, s"Missing item in composition: $body")
      val compositionItem = comp.hcursor.downField("item").focus.get
      assert(compositionItem.hcursor.downField("id").focus.isDefined,    s"Missing item.id in composition: $body")
      assert(compositionItem.hcursor.downField("name").focus.isDefined,  s"Missing item.name in composition: $body")
      assertEquals(compositionItem.hcursor.downField("id").as[String].toOption, Some(itemId))
  }

  test("GET /storefront/:slug does not return deactivated items") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      _        <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.DELETE, Uri.unsafeFromString(s"/dashboard/items/$itemId"))
                      .withHeaders(authHeader(auth.token))
                  )
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      val items = json.hcursor.downField("items").focus.get.asArray.get
      assert(items.isEmpty, s"Expected no active items in storefront but got: $body")
  }

  test("GET /storefront/:slug does not return deactivated combos") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      itemId   <- createItem(ctx, auth.token)
      comboId  <- createCombo(ctx, auth.token, itemId)
      _        <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.DELETE, Uri.unsafeFromString(s"/dashboard/combos/$comboId"))
                      .withHeaders(authHeader(auth.token))
                  )
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      val combos = json.hcursor.downField("combos").focus.get.asArray.get
      assert(combos.isEmpty, s"Expected no active combos in storefront but got: $body")
  }

  test("GET /storefront/:slug returns 404 for unknown slug") {
    for
      ctx      <- makeCtx
      request   = Request[IO](Method.GET, uri"/storefront/no-such-provider-slug")
      response <- ctx.allRoutes.orNotFound(request)
    yield assertEquals(response.status, Status.NotFound)
  }

  test("GET /storefront/:slug is public — no Authorization header needed") {
    for
      ctx      <- makeCtx
      auth     <- signupAndLogin(ctx)
      slug     <- getSlug(ctx, auth.id)
      request   = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug"))
      response <- ctx.allRoutes.orNotFound(request)
    yield assert(
      response.status != Status.Unauthorized,
      s"Expected public access (not 401) but got ${response.status}"
    )
  }
