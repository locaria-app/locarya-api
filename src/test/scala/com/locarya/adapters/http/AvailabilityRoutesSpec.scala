package com.locarya.adapters.http

import cats.effect.IO
import com.locarya.domain.models.*
import sttp.tapir.AnyEndpoint
import com.locarya.domain.services.{AvailabilityServiceImpl, StorefrontServiceImpl}
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemImageRepository,
  InMemoryItemRepository,
  InMemoryProviderRepository
}
import io.circe.Json
import io.circe.parser.parse
import java.time.LocalDate
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AvailabilityRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val customerId = CustomerId.generate
  private val date       = LocalDate.of(2026, 9, 1)
  private val price      = Money.fromAmount(BigDecimal("100.00")).toOption.get
  private val slug       = "loja-de-festa"

  private case class Ctx(
    routes:      HttpRoutes[IO],
    providerId:  ProviderId,
    itemRepo:    InMemoryItemRepository[IO],
    comboRepo:   InMemoryComboRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  )

  private def makeProvider(repo: InMemoryProviderRepository[IO]): IO[ProviderId] =
    val provider = Provider.create(
      id             = ProviderId.generate,
      email          = Email.fromString("locador@loja.com").toOption.get,
      taxId          = TaxId.fromCNPJ(CNPJ.fromString("11.222.333/0001-81").toOption.get),
      businessName   = "Loja de Festa LTDA",
      tradeName      = "Loja de Festa",
      city           = "São Paulo",
      state          = "SP",
      storefrontSlug = StorefrontSlug.fromString(slug).toOption.get
    ).toOption.get
    repo.create(provider).as(provider.id)

  private def makeCtx: IO[Ctx] =
    for
      providerRepo <- InMemoryProviderRepository.make[IO]
      itemRepo     <- InMemoryItemRepository.make[IO]
      imageRepo    <- InMemoryItemImageRepository.make[IO]
      comboRepo    <- InMemoryComboRepository.make[IO]
      bookingRepo  <- InMemoryBookingRepository.make[IO]
      providerId   <- makeProvider(providerRepo)
      availSvc      = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      storefrontSvc = StorefrontServiceImpl[IO](providerRepo, itemRepo, imageRepo, comboRepo)
      routes        = AvailabilityRoutes.routes[IO](availSvc, storefrontSvc)
    yield Ctx(routes, providerId, itemRepo, comboRepo, bookingRepo)

  private def putItem(ctx: Ctx, stock: Int = 1): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = ctx.providerId,
      name                 = "Cama Elástica",
      description          = "Para festa",
      dailyRate            = price,
      stock                = stock,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.itemRepo.create(item).as(item)

  private def putCombo(ctx: Ctx, items: List[(Item, Int)]): IO[Combo] =
    val combo = Combo.create(
      id                   = ComboId.generate,
      providerId           = ctx.providerId,
      name                 = "Combo Festa",
      description          = "Combo de festa",
      dailyRate            = price,
      items                = items.map((i, q) => ComboItemDefinition(i.id, q)),
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.comboRepo.create(combo).as(combo)

  private def putBooking(ctx: Ctx, items: List[BookingItem]): IO[Unit] =
    val booking = Booking.create(
      id          = BookingId.generate,
      providerId  = ctx.providerId,
      customerId  = customerId,
      items       = items,
      startDate   = date,
      endDate     = date,
      totalAmount = price,
      status      = BookingStatus.Confirmed
    ).toOption.get
    ctx.bookingRepo.create(booking).void

  private def get(ctx: Ctx, query: String): IO[Response[IO]] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/storefront/$slug/availability?$query"))
    ctx.routes.orNotFound(req)

  private def itemsOf(json: Json): Vector[Json] =
    json.hcursor.downField("items").focus.get.asArray.get

  private def entryFor(json: Json, id: String): Json =
    itemsOf(json).find(_.hcursor.downField("id").as[String].toOption.contains(id))
      .getOrElse(fail(s"no entry for $id"))

  private def availableOf(entry: Json): Boolean =
    entry.hcursor.downField("available").as[Boolean].toOption.get

  // ── Basket check (items param present) ─────────────────────────────────────────

  test("returns 200 with available=true when stock is enough") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx, stock = 2)
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      val entry = entryFor(json, item.id.value)
      assertEquals(availableOf(entry), true)
      assertEquals(entry.hcursor.downField("kind").as[String].toOption, Some("item"))
      assertEquals(entry.hcursor.downField("availableQty").as[Int].toOption, Some(2))
  }

  test("returns 200 with available=false and remaining qty when stock is short") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx, stock = 1)
      _        <- putBooking(ctx, List(BookedIndividualItem(item.id, 1)))
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      val entry = entryFor(json, item.id.value)
      assertEquals(availableOf(entry), false)
      assertEquals(entry.hcursor.downField("availableQty").as[Int].toOption, Some(0))
  }

  test("supports multiple items separated by commas, preserving each entry") {
    for
      ctx      <- makeCtx
      a        <- putItem(ctx, stock = 5)
      b        <- putItem(ctx, stock = 0)
      response <- get(ctx, s"items=${a.id.value}:1,${b.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(itemsOf(json).size, 2)
      assertEquals(availableOf(entryFor(json, a.id.value)), true)
      assertEquals(availableOf(entryFor(json, b.id.value)), false)
  }

  // ── Catalog listing (no items param) ───────────────────────────────────────────

  test("without items, returns availability for the provider's whole catalog") {
    for
      ctx      <- makeCtx
      a        <- putItem(ctx, stock = 2)
      b        <- putItem(ctx, stock = 0)
      combo    <- putCombo(ctx, List((a, 1)))
      response <- get(ctx, s"date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(itemsOf(json).size, 3) // 2 items + 1 combo
      assertEquals(availableOf(entryFor(json, a.id.value)), true)
      assertEquals(availableOf(entryFor(json, b.id.value)), false)
      val comboEntry = entryFor(json, combo.id.value)
      assertEquals(comboEntry.hcursor.downField("kind").as[String].toOption, Some("combo"))
  }

  test("catalog listing: a combo can be unavailable while its constituent items are available") {
    for
      ctx      <- makeCtx
      a        <- putItem(ctx, stock = 0)
      b        <- putItem(ctx, stock = 5)
      combo    <- putCombo(ctx, List((a, 1), (b, 1)))
      response <- get(ctx, s"date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(availableOf(entryFor(json, combo.id.value)), false)
      assertEquals(availableOf(entryFor(json, b.id.value)), true)
  }

  test("without items, returns 404 when the storefront slug does not exist") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/no-such-slug/availability?date=$date"))
    for
      ctx      <- makeCtx
      response <- ctx.routes.orNotFound(req)
    yield assertEquals(response.status, Status.NotFound)
  }

  // ── Malformed input ────────────────────────────────────────────────────────────

  test("returns 400 when date param is missing") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("returns 400 when items entry is malformed") {
    for
      ctx      <- makeCtx
      response <- get(ctx, s"items=not-a-uuid:1&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("returns 400 when qty is not an integer") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:notanumber&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("returns 400 when qty is zero or negative") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:0&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("returns 400 when date is malformed") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1&date=not-a-date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  // ── excludeBookingId & access ──────────────────────────────────────────────────

  test("honours excludeBookingId") {
    for
      ctx       <- makeCtx
      item      <- putItem(ctx, stock = 1)
      bookingId  = BookingId.generate
      booking    = Booking.create(
                     id          = bookingId,
                     providerId  = ctx.providerId,
                     customerId  = customerId,
                     items       = List(BookedIndividualItem(item.id, 1)),
                     startDate   = date,
                     endDate     = date,
                     totalAmount = price,
                     status      = BookingStatus.Confirmed
                   ).toOption.get
      _         <- ctx.bookingRepo.create(booking)
      response  <- get(ctx, s"items=${item.id.value}:1&date=$date&excludeBookingId=${bookingId.value}")
      body      <- response.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(availableOf(entryFor(json, item.id.value)), true)
  }

  test("is public — no Authorization header required") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
    yield assert(
      response.status != Status.Unauthorized,
      s"Expected public access (not 401) but got ${response.status}"
    )
  }

  test("AvailabilityRoutes.allEndpoints is non-empty and documents path and query params") {
    assert(AvailabilityRoutes.allEndpoints.nonEmpty)
    val desc = AvailabilityRoutes.allEndpoints.map(_.show).mkString
    assert(desc.contains("api") && desc.contains("v1"), s"Expected 'api/v1' prefix in endpoint: $desc")
    assert(desc.contains("date"),            s"Expected 'date' query param in endpoint: $desc")
    assert(desc.contains("items"),           s"Expected 'items' query param in endpoint: $desc")
    assert(desc.contains("excludeBookingId"), s"Expected 'excludeBookingId' query param in endpoint: $desc")
  }
