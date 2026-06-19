package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AuthServiceImpl, PaymentServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryPaymentRepository,
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

class PaymentRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-payment"
  private val date          = LocalDate.of(2026, 9, 1)
  private val totalMoney    = Money.fromAmount(BigDecimal("500.00")).toOption.get

  private case class Ctx(
    allRoutes:   HttpRoutes[IO],
    paymentRepo: InMemoryPaymentRepository[IO],
    bookingRepo: InMemoryBookingRepository[IO],
    providerRepo: InMemoryProviderRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      paymentRepo   <- InMemoryPaymentRepository.make[IO]
      providerSvc    = ProviderServiceImpl[IO](providerRepo)
      authSvc        = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      paymentSvc     = PaymentServiceImpl[IO](bookingRepo, paymentRepo)
      authRoutes     = AuthRoutes.routes[IO](providerSvc, authSvc)
      paymentRoutes  = PaymentRoutes.routes[IO](paymentSvc, testJwtSecret)
    yield Ctx(authRoutes <+> paymentRoutes, paymentRepo, bookingRepo, providerRepo)

  private val signupBody =
    """{
      "email":    "locador@payment.com",
      "password": "securepassword123",
      "name":     "Locador Payment",
      "city":     "Sao Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@payment.com","password":"securepassword123"}"""

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

  private def seedBooking(ctx: Ctx, providerId: ProviderId): IO[BookingId] =
    val booking = Booking.create(
      id          = BookingId.generate,
      providerId  = providerId,
      customerId  = CustomerId.generate,
      items       = List(BookedIndividualItem(ItemId.generate, 1)),
      startDate   = date,
      endDate     = date,
      totalAmount = totalMoney,
      status      = BookingStatus.Confirmed
    ).toOption.get
    ctx.bookingRepo.create(booking).as(booking.id)

  private def postPayment(ctx: Ctx, bookingId: String, body: String, token: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.POST, Uri.unsafeFromString(s"/dashboard/bookings/$bookingId/payments"))
        .withEntity(body)
        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    )

  private def getPayments(ctx: Ctx, bookingId: String, token: String): IO[Response[IO]] =
    ctx.allRoutes.orNotFound(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/dashboard/bookings/$bookingId/payments"))
        .withHeaders(authHeader(token))
    )

  private val validPaymentBody =
    """{"amount":200.00,"method":"pix_manual"}"""

  // ── Auth guard ────────────────────────────────────────────────────────────

  test("POST /dashboard/bookings/:id/payments returns 401 without Authorization header") {
    for
      ctx      <- makeCtx
      bookingId = BookingId.generate.value
      resp     <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.POST, Uri.unsafeFromString(s"/dashboard/bookings/$bookingId/payments"))
                      .withEntity(validPaymentBody)
                      .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                  )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  test("GET /dashboard/bookings/:id/payments returns 401 without Authorization header") {
    for
      ctx      <- makeCtx
      bookingId = BookingId.generate.value
      resp     <- ctx.allRoutes.orNotFound(
                    Request[IO](Method.GET, Uri.unsafeFromString(s"/dashboard/bookings/$bookingId/payments"))
                  )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  // ── POST /dashboard/bookings/:id/payments ─────────────────────────────────

  test("POST /dashboard/bookings/:id/payments returns 201 with paymentId and status=confirmed") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      resp      <- postPayment(ctx, bookingId.value, validPaymentBody, auth.token)
      body      <- resp.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Created)
      assert(json.hcursor.downField("paymentId").focus.isDefined, body)
      assertEquals(json.hcursor.downField("status").as[String].toOption, Some("confirmed"))
  }

  test("POST /dashboard/bookings/:id/payments persists payment with method=pix_manual") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      resp      <- postPayment(ctx, bookingId.value, validPaymentBody, auth.token)
      body      <- resp.as[String]
      paymentId  = parse(body).toOption.get.hcursor.downField("paymentId").as[String].toOption.get
      found     <- ctx.paymentRepo.findByBooking(bookingId)
    yield
      assertEquals(found.size, 1)
      assertEquals(found.head.method, PaymentMethod.PixManual)
      assertEquals(found.head.amount.amount, BigDecimal("200.0"))
  }

  test("POST /dashboard/bookings/:id/payments accepts optional note") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      body       = """{"amount":100.00,"method":"pix_manual","note":"entrada"}"""
      resp      <- postPayment(ctx, bookingId.value, body, auth.token)
      found     <- ctx.paymentRepo.findByBooking(bookingId)
    yield
      assertEquals(resp.status, Status.Created)
      assertEquals(found.head.note, Some("entrada"))
  }

  test("POST /dashboard/bookings/:id/payments returns 404 for unknown booking") {
    for
      ctx   <- makeCtx
      auth  <- signupAndLogin(ctx)
      bogus  = BookingId.generate.value
      resp  <- postPayment(ctx, bogus, validPaymentBody, auth.token)
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("POST /dashboard/bookings/:id/payments returns 403 for another provider's booking") {
    for
      ctx       <- makeCtx
      auth1     <- signupAndLogin(ctx)
      pid1       = ProviderId.fromString(auth1.id).toOption.get
      bookingId <- seedBooking(ctx, pid1)
      // signup second provider
      _         <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/signup")
                       .withEntity("""{
                         "email":"other@payment.com","password":"otherpass123",
                         "name":"Other Locador","city":"Rio","state":"RJ","cnpj":"11.222.333/0001-81"
                       }""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      loginResp <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/login")
                       .withEntity("""{"email":"other@payment.com","password":"otherpass123"}""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      body2     <- loginResp.as[String]
      token2     = parse(body2).toOption.get.hcursor.downField("token").as[String].toOption.get
      resp      <- postPayment(ctx, bookingId.value, validPaymentBody, token2)
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("POST /dashboard/bookings/:id/payments returns 400 for zero amount") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      resp      <- postPayment(ctx, bookingId.value, """{"amount":0,"method":"pix_manual"}""", auth.token)
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("POST /dashboard/bookings/:id/payments returns 400 for malformed body") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      resp      <- postPayment(ctx, bookingId.value, """{"bad":"json"}""", auth.token)
    yield assertEquals(resp.status, Status.BadRequest)
  }

  // ── GET /dashboard/bookings/:id/payments ─────────────────────────────────

  test("GET /dashboard/bookings/:id/payments returns both payments with correct amounts") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      _         <- postPayment(ctx, bookingId.value, """{"amount":100.00,"method":"pix_manual"}""", auth.token)
      _         <- postPayment(ctx, bookingId.value, """{"amount":200.00,"method":"pix_manual"}""", auth.token)
      resp      <- getPayments(ctx, bookingId.value, auth.token)
      body      <- resp.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      val payments = json.hcursor.downField("payments").as[io.circe.Json].toOption
        .flatMap(_.asArray)
        .getOrElse(json.asArray.getOrElse(Vector.empty))
      assertEquals(payments.size, 2)
  }

  test("GET /dashboard/bookings/:id/payments returns summary with balanceDue") {
    for
      ctx       <- makeCtx
      auth      <- signupAndLogin(ctx)
      pid        = ProviderId.fromString(auth.id).toOption.get
      bookingId <- seedBooking(ctx, pid)
      _         <- postPayment(ctx, bookingId.value, """{"amount":200.00,"method":"pix_manual"}""", auth.token)
      resp      <- getPayments(ctx, bookingId.value, auth.token)
      body      <- resp.as[String]
      json       = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      val summary = json.hcursor.downField("summary")
      assert(summary.downField("total").as[BigDecimal].isRight,      body)
      assert(summary.downField("paid").as[BigDecimal].isRight,       body)
      assert(summary.downField("balanceDue").as[BigDecimal].isRight, body)
      assertEquals(summary.downField("paid").as[BigDecimal].toOption, Some(BigDecimal("200.0")))
      assertEquals(summary.downField("balanceDue").as[BigDecimal].toOption, Some(BigDecimal("300.0")))
  }

  test("GET /dashboard/bookings/:id/payments returns 403 for another provider's booking") {
    for
      ctx       <- makeCtx
      auth1     <- signupAndLogin(ctx)
      pid1       = ProviderId.fromString(auth1.id).toOption.get
      bookingId <- seedBooking(ctx, pid1)
      _         <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/signup")
                       .withEntity("""{
                         "email":"other2@payment.com","password":"otherpass123",
                         "name":"Other2 Locador","city":"Rio","state":"RJ","cnpj":"11.222.333/0001-81"
                       }""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      loginResp <- ctx.allRoutes.orNotFound(
                     Request[IO](Method.POST, uri"/auth/login")
                       .withEntity("""{"email":"other2@payment.com","password":"otherpass123"}""")
                       .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                   )
      body2     <- loginResp.as[String]
      token2     = parse(body2).toOption.get.hcursor.downField("token").as[String].toOption.get
      resp      <- getPayments(ctx, bookingId.value, token2)
    yield assertEquals(resp.status, Status.Forbidden)
  }

  test("GET /dashboard/bookings/:id/payments returns 404 for unknown booking") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- getPayments(ctx, BookingId.generate.value, auth.token)
    yield assertEquals(resp.status, Status.NotFound)
  }
