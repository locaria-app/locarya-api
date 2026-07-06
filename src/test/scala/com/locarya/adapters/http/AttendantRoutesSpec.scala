package com.locarya.adapters.http

import cats.effect.IO
import cats.syntax.semigroupk.*
import com.locarya.domain.models.*
import com.locarya.domain.services.{AttendantServiceImpl, AuthServiceImpl, ProviderServiceImpl}
import com.locarya.helpers.{InMemoryAttendantRepository, InMemoryBookingRepository, InMemoryProviderRepository}
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

class AttendantRoutesSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val testJwtSecret = "test-jwt-secret-attendant"

  private case class Ctx(
    allRoutes:     HttpRoutes[IO],
    attendantRepo: InMemoryAttendantRepository[IO],
    bookingRepo:   InMemoryBookingRepository[IO],
    providerRepo:  InMemoryProviderRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      providerRepo  <- InMemoryProviderRepository.make[IO]
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      providerSvc    = ProviderServiceImpl[IO](providerRepo)
      authSvc        = AuthServiceImpl[IO](providerRepo, testJwtSecret)
      attendantSvc   = AttendantServiceImpl[IO](attendantRepo, bookingRepo)
      authRoutes     = AuthRoutes.routes[IO](providerSvc, authSvc)
      attendantRts   = AttendantRoutes.routes[IO](attendantSvc, testJwtSecret)
    yield Ctx(authRoutes <+> attendantRts, attendantRepo, bookingRepo, providerRepo)

  private val signupBody =
    """{
      "email":    "locador@attendant.com",
      "password": "Securepass123",
      "name":     "Locador Attendant",
      "city":     "Sao Paulo",
      "state":    "SP",
      "cnpj":     "11.222.333/0001-81"
    }"""

  private val loginBody =
    """{"email":"locador@attendant.com","password":"Securepass123"}"""

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

  private def authHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private def postAttendant(ctx: Ctx, token: String, name: String = "Joao Silva", phone: String = "11999990000"): IO[Response[IO]] =
    val body = s"""{"name":"$name","phone":"$phone"}"""
    ctx.allRoutes.orNotFound(
      Request[IO](Method.POST, uri"/api/v1/dashboard/attendants")
        .withEntity(body)
        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(token))
    )

  private def getAttendantId(resp: Response[IO]): IO[String] =
    resp.as[String].map(body => parse(body).toOption.get.hcursor.downField("attendantId").as[String].toOption.get)

  // ── Auth guard ────────────────────────────────────────────────────────────

  test("POST /dashboard/attendants returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.POST, uri"/api/v1/dashboard/attendants")
                  .withEntity("""{"name":"X","phone":"1"}""")
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
              )
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  test("GET /dashboard/attendants returns 401 without Authorization header") {
    for
      ctx  <- makeCtx
      resp <- ctx.allRoutes.orNotFound(Request[IO](Method.GET, uri"/api/v1/dashboard/attendants"))
    yield assertEquals(resp.status, Status.Unauthorized)
  }

  // ── POST /dashboard/attendants ────────────────────────────────────────────

  test("POST /dashboard/attendants creates attendant and returns 201 with attendantId") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- postAttendant(ctx, auth.token)
      body <- resp.as[String]
      json  = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Created)
      assert(json.hcursor.downField("attendantId").focus.isDefined, body)
  }

  test("POST /dashboard/attendants persists attendant with correct name and phone") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      resp        <- postAttendant(ctx, auth.token, name = "Maria Santos", phone = "11888880000")
      body        <- resp.as[String]
      attendantId  = parse(body).toOption.get.hcursor.downField("attendantId").as[String].toOption.get
      stored      <- ctx.attendantRepo.findById(AttendantId.fromString(attendantId).toOption.get)
    yield
      assert(stored.isDefined, "Expected attendant to be persisted")
      assertEquals(stored.get.name, "Maria Santos")
      assertEquals(stored.get.phone, "11888880000")
      assertEquals(stored.get.isActive, true)
  }

  test("POST /dashboard/attendants returns 400 for empty name") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- postAttendant(ctx, auth.token, name = "")
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("POST /dashboard/attendants returns 400 for malformed body") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.POST, uri"/api/v1/dashboard/attendants")
                  .withEntity("""{"bad":"json"}""")
                  .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
              )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  // ── PUT /dashboard/attendants/:id ─────────────────────────────────────────

  test("PUT /dashboard/attendants/:id updates name and phone and returns 200") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      createResp  <- postAttendant(ctx, auth.token)
      attendantId <- getAttendantId(createResp)
      updateBody   = """{"name":"Maria Updated","phone":"11777770000"}"""
      updateResp  <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$attendantId"))
                         .withEntity(updateBody)
                         .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
                     )
      stored      <- ctx.attendantRepo.findById(AttendantId.fromString(attendantId).toOption.get)
    yield
      assertEquals(updateResp.status, Status.Ok)
      assertEquals(stored.get.name, "Maria Updated")
      assertEquals(stored.get.phone, "11777770000")
  }

  test("PUT /dashboard/attendants/:id returns 404 for unknown attendant") {
    for
      ctx    <- makeCtx
      auth   <- signupAndLogin(ctx)
      bogus   = AttendantId.generate.value
      resp   <- ctx.allRoutes.orNotFound(
                  Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$bogus"))
                    .withEntity("""{"name":"X","phone":"1"}""")
                    .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
                )
    yield assertEquals(resp.status, Status.NotFound)
  }

  // ── DELETE /dashboard/attendants/:id ─────────────────────────────────────

  test("DELETE /dashboard/attendants/:id soft-deletes and returns 200") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      createResp  <- postAttendant(ctx, auth.token)
      attendantId <- getAttendantId(createResp)
      deleteResp  <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$attendantId"))
                         .withHeaders(authHeader(auth.token))
                     )
      stored      <- ctx.attendantRepo.findById(AttendantId.fromString(attendantId).toOption.get)
    yield
      assertEquals(deleteResp.status, Status.Ok)
      assertEquals(stored.get.isActive, false)
  }

  test("DELETE /dashboard/attendants/:id returns 404 for unknown attendant") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      bogus = AttendantId.generate.value
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$bogus"))
                  .withHeaders(authHeader(auth.token))
              )
    yield assertEquals(resp.status, Status.NotFound)
  }

  // ── GET /dashboard/attendants ─────────────────────────────────────────────

  test("GET /dashboard/attendants returns 200 with list of active attendants") {
    for
      ctx  <- makeCtx
      auth <- signupAndLogin(ctx)
      _    <- postAttendant(ctx, auth.token, name = "Active One")
      _    <- postAttendant(ctx, auth.token, name = "Active Two")
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.GET, uri"/api/v1/dashboard/attendants")
                  .withHeaders(authHeader(auth.token))
              )
      body <- resp.as[String]
      json  = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.asArray.map(_.size), Some(2))
  }

  test("GET /dashboard/attendants excludes deactivated attendants") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      createResp  <- postAttendant(ctx, auth.token, name = "ToDeactivate")
      attendantId <- getAttendantId(createResp)
      _           <- postAttendant(ctx, auth.token, name = "Active")
      _           <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$attendantId"))
                         .withHeaders(authHeader(auth.token))
                     )
      resp <- ctx.allRoutes.orNotFound(
                Request[IO](Method.GET, uri"/api/v1/dashboard/attendants")
                  .withHeaders(authHeader(auth.token))
              )
      body <- resp.as[String]
      json  = parse(body).toOption.get
    yield
      assertEquals(resp.status, Status.Ok)
      assertEquals(json.asArray.map(_.size), Some(1))
  }

  // ── PUT /api/v1/dashboard/bookings/:id/attendants ────────────────────────────────

  test("PUT /api/v1/dashboard/bookings/:id/attendants assigns attendant and returns 200") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      createResp  <- postAttendant(ctx, auth.token)
      attendantId <- getAttendantId(createResp)
      bookingId    = BookingId.generate.value
      assignResp  <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/bookings/$bookingId/attendants"))
                         .withEntity(s"""{"attendantIds":["$attendantId"]}""")
                         .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
                     )
      assigned    <- ctx.attendantRepo.findByBooking(BookingId.fromString(bookingId).toOption.get)
    yield
      assertEquals(assignResp.status, Status.Ok)
      assertEquals(assigned.size, 1)
      assertEquals(assigned.head.id.value, attendantId)
  }

  test("PUT /api/v1/dashboard/bookings/:id/attendants returns 404 for unknown attendant id") {
    for
      ctx        <- makeCtx
      auth       <- signupAndLogin(ctx)
      bookingId   = BookingId.generate.value
      bogusId     = AttendantId.generate.value
      resp       <- ctx.allRoutes.orNotFound(
                      Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/bookings/$bookingId/attendants"))
                        .withEntity(s"""{"attendantIds":["$bogusId"]}""")
                        .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
                    )
    yield assertEquals(resp.status, Status.NotFound)
  }

  test("PUT /api/v1/dashboard/bookings/:id/attendants returns 400 when attendant is inactive") {
    for
      ctx         <- makeCtx
      auth        <- signupAndLogin(ctx)
      createResp  <- postAttendant(ctx, auth.token)
      attendantId <- getAttendantId(createResp)
      _           <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/v1/dashboard/attendants/$attendantId"))
                         .withHeaders(authHeader(auth.token))
                     )
      bookingId    = BookingId.generate.value
      resp        <- ctx.allRoutes.orNotFound(
                       Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/bookings/$bookingId/attendants"))
                         .withEntity(s"""{"attendantIds":["$attendantId"]}""")
                         .withHeaders(Header.Raw(ci"Content-Type", "application/json"), authHeader(auth.token))
                     )
    yield assertEquals(resp.status, Status.BadRequest)
  }

  test("PUT /api/v1/dashboard/bookings/:id/attendants returns 401 without Authorization header") {
    for
      ctx        <- makeCtx
      bookingId   = BookingId.generate.value
      bogusId     = AttendantId.generate.value
      resp       <- ctx.allRoutes.orNotFound(
                      Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/v1/dashboard/bookings/$bookingId/attendants"))
                        .withEntity(s"""{"attendantIds":["$bogusId"]}""")
                        .withHeaders(Header.Raw(ci"Content-Type", "application/json"))
                    )
    yield assertEquals(resp.status, Status.Unauthorized)
  }
