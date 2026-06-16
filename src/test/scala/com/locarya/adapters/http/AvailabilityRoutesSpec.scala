package com.locarya.adapters.http

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.services.AvailabilityServiceImpl
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemRepository
}
import io.circe.parser.parse
import java.time.LocalDate
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AvailabilityRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId = ProviderId.generate
  private val customerId = CustomerId.generate
  private val date       = LocalDate.of(2026, 9, 1)
  private val price      = Money.fromAmount(BigDecimal("100.00")).toOption.get
  private val slug       = "loja-de-festa"

  private case class Ctx(
    routes:      HttpRoutes[IO],
    itemRepo:    InMemoryItemRepository[IO],
    comboRepo:   InMemoryComboRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      itemRepo    <- InMemoryItemRepository.make[IO]
      comboRepo   <- InMemoryComboRepository.make[IO]
      bookingRepo <- InMemoryBookingRepository.make[IO]
      svc          = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
      routes       = AvailabilityRoutes.routes[IO](svc)
    yield Ctx(routes, itemRepo, comboRepo, bookingRepo)

  private def putItem(ctx: Ctx, stock: Int = 1): IO[Item] =
    val item = Item.create(
      id                   = ItemId.generate,
      providerId           = providerId,
      name                 = "Cama Elástica",
      description          = "Para festa",
      dailyRate            = price,
      stock                = stock,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get
    ctx.itemRepo.create(item).map(_ => item)

  private def putBooking(
    ctx:   Ctx,
    items: List[BookingItem]
  ): IO[Unit] =
    val booking = Booking.create(
      id          = BookingId.generate,
      providerId  = providerId,
      customerId  = customerId,
      items       = items,
      startDate   = date,
      endDate     = date,
      totalAmount = price,
      status      = BookingStatus.Confirmed
    ).toOption.get
    ctx.bookingRepo.create(booking).void

  private def get(ctx: Ctx, query: String): IO[Response[IO]] =
    val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/storefront/$slug/availability?$query"))
    ctx.routes.orNotFound(req)

  // ── Happy paths ──────────────────────────────────────────────────────────────

  test("GET /storefront/:slug/availability returns 200 with available=true when stock is enough") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx, stock = 2)
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json.hcursor.downField("available").as[Boolean].toOption, Some(true))
      assertEquals(
        json.hcursor.downField("unavailableItems").focus.get.asArray.get.size,
        0
      )
  }

  test("GET /storefront/:slug/availability returns 200 with available=false and unavailableItems when stock is short") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx, stock = 1)
      _        <- putBooking(ctx, List(BookedIndividualItem(item.id, 1)))
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
      unavail   = json.hcursor.downField("unavailableItems").focus.get.asArray.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json.hcursor.downField("available").as[Boolean].toOption, Some(false))
      assertEquals(unavail.size, 1)
      assertEquals(unavail.head.hcursor.downField("itemId").as[String].toOption, Some(item.id.value))
      assertEquals(unavail.head.hcursor.downField("reason").as[String].toOption, Some("stock depleted"))
  }

  test("GET /storefront/:slug/availability supports multiple items separated by commas") {
    for
      ctx      <- makeCtx
      a        <- putItem(ctx, stock = 5)
      b        <- putItem(ctx, stock = 0)
      response <- get(ctx, s"items=${a.id.value}:1,${b.id.value}:1&date=$date")
      body     <- response.as[String]
      json      = parse(body).toOption.get
      unavail   = json.hcursor.downField("unavailableItems").focus.get.asArray.get
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(json.hcursor.downField("available").as[Boolean].toOption, Some(false))
      assertEquals(unavail.map(_.hcursor.downField("itemId").as[String].toOption.get).toSet, Set(b.id.value))
  }

  // ── Malformed input ──────────────────────────────────────────────────────────

  test("GET /storefront/:slug/availability returns 400 when items param is missing") {
    for
      ctx      <- makeCtx
      response <- get(ctx, s"date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /storefront/:slug/availability returns 400 when date param is missing") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /storefront/:slug/availability returns 400 when items entry is malformed") {
    for
      ctx      <- makeCtx
      response <- get(ctx, s"items=not-a-uuid:1&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /storefront/:slug/availability returns 400 when qty is not an integer") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:notanumber&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /storefront/:slug/availability returns 400 when qty is zero or negative") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:0&date=$date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  test("GET /storefront/:slug/availability returns 400 when date is malformed") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1&date=not-a-date")
    yield assertEquals(response.status, Status.BadRequest)
  }

  // ── excludeBookingId ─────────────────────────────────────────────────────────

  test("GET /storefront/:slug/availability honours excludeBookingId") {
    for
      ctx       <- makeCtx
      item      <- putItem(ctx, stock = 1)
      bookingId  = BookingId.generate
      booking    = Booking.create(
                     id          = bookingId,
                     providerId  = providerId,
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
      assertEquals(json.hcursor.downField("available").as[Boolean].toOption, Some(true))
  }

  test("GET /storefront/:slug/availability is public — no Authorization header required") {
    for
      ctx      <- makeCtx
      item     <- putItem(ctx)
      response <- get(ctx, s"items=${item.id.value}:1&date=$date")
    yield assert(
      response.status != Status.Unauthorized,
      s"Expected public access (not 401) but got ${response.status}"
    )
  }
