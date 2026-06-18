package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.*
import com.locarya.helpers.{InMemoryAttendantRepository, InMemoryBookingRepository, InMemoryItemRepository, InMemoryProviderRepository}
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.Logger

class AttendantServiceSpec extends CatsEffectSuite:

  given Logger[IO] = NoOpLogger[IO]

  private val providerId  = ProviderId.generate
  private val providerId2 = ProviderId.generate

  private case class Ctx(
    svc:          AttendantService[IO],
    attendantRepo: InMemoryAttendantRepository[IO],
    bookingRepo:   InMemoryBookingRepository[IO]
  )

  private def makeCtx: IO[Ctx] =
    for
      attendantRepo <- InMemoryAttendantRepository.make[IO]
      bookingRepo   <- InMemoryBookingRepository.make[IO]
      svc            = AttendantServiceImpl[IO](attendantRepo, bookingRepo)
    yield Ctx(svc, attendantRepo, bookingRepo)

  private def createRequest(providerId: ProviderId = this.providerId, name: String = "João Silva", phone: String = "11999990000") =
    CreateAttendantRequest(providerId, name, phone)

  // ── create ────────────────────────────────────────────────────────────────

  test("createAttendant stores attendant and returns its id") {
    for
      ctx <- makeCtx
      id  <- ctx.svc.createAttendant(createRequest())
      a   <- ctx.attendantRepo.findById(id)
    yield
      assert(a.isDefined, "Expected attendant to be stored")
      assertEquals(a.get.name, "João Silva")
      assertEquals(a.get.phone, "11999990000")
      assertEquals(a.get.providerId, providerId)
      assertEquals(a.get.isActive, true)
  }

  test("createAttendant returns InvalidInput for empty name") {
    for
      ctx    <- makeCtx
      result <- ctx.svc.createAttendant(createRequest(name = "")).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.InvalidInput => ()
        case other                          => fail(s"Expected InvalidInput, got $other")
      }
  }

  // ── update ────────────────────────────────────────────────────────────────

  test("updateAttendant changes name and phone") {
    for
      ctx <- makeCtx
      id  <- ctx.svc.createAttendant(createRequest())
      _   <- ctx.svc.updateAttendant(UpdateAttendantRequest(id, providerId, "Maria Santos", "11888880000"))
      a   <- ctx.attendantRepo.findById(id)
    yield
      assertEquals(a.get.name, "Maria Santos")
      assertEquals(a.get.phone, "11888880000")
  }

  test("updateAttendant preserves isActive state") {
    for
      ctx <- makeCtx
      id  <- ctx.svc.createAttendant(createRequest())
      _   <- ctx.svc.deactivateAttendant(id, providerId)
      _   <- ctx.svc.updateAttendant(UpdateAttendantRequest(id, providerId, "Maria Santos", "11888880000"))
      a   <- ctx.attendantRepo.findById(id)
    yield assertEquals(a.get.isActive, false)
  }

  test("updateAttendant raises AttendantNotFound for unknown id") {
    for
      ctx    <- makeCtx
      bogus   = AttendantId.generate
      result <- ctx.svc.updateAttendant(UpdateAttendantRequest(bogus, providerId, "X", "1")).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.AttendantNotFound => ()
        case other                               => fail(s"Expected AttendantNotFound, got $other")
      }
  }

  test("updateAttendant raises Forbidden when provider does not own attendant") {
    for
      ctx    <- makeCtx
      id     <- ctx.svc.createAttendant(createRequest(providerId = providerId))
      result <- ctx.svc.updateAttendant(UpdateAttendantRequest(id, providerId2, "X", "1")).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.Forbidden => ()
        case other                       => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── deactivate ────────────────────────────────────────────────────────────

  test("deactivateAttendant sets isActive to false") {
    for
      ctx <- makeCtx
      id  <- ctx.svc.createAttendant(createRequest())
      _   <- ctx.svc.deactivateAttendant(id, providerId)
      a   <- ctx.attendantRepo.findById(id)
    yield assertEquals(a.get.isActive, false)
  }

  test("deactivateAttendant raises Forbidden for wrong provider") {
    for
      ctx    <- makeCtx
      id     <- ctx.svc.createAttendant(createRequest())
      result <- ctx.svc.deactivateAttendant(id, providerId2).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.Forbidden => ()
        case other                       => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── listActiveAttendants ──────────────────────────────────────────────────

  test("listActiveAttendants returns only active attendants for the provider") {
    for
      ctx <- makeCtx
      id1 <- ctx.svc.createAttendant(createRequest(name = "Active"))
      id2 <- ctx.svc.createAttendant(createRequest(name = "ToDeactivate"))
      _   <- ctx.svc.deactivateAttendant(id2, providerId)
      _   <- ctx.svc.createAttendant(createRequest(providerId = providerId2, name = "Other Provider"))
      list <- ctx.svc.listActiveAttendants(providerId)
    yield
      assertEquals(list.size, 1)
      assertEquals(list.head.id, id1)
      assert(list.forall(_.isActive))
      assert(list.forall(_.providerId == providerId))
  }

  // ── assignAttendants ──────────────────────────────────────────────────────

  test("assignAttendants stores join and findByBooking returns assigned attendants") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      id        <- ctx.svc.createAttendant(createRequest())
      _         <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, List(id)))
      assigned  <- ctx.attendantRepo.findByBooking(bookingId)
    yield
      assertEquals(assigned.size, 1)
      assertEquals(assigned.head.id, id)
  }

  test("assignAttendants — same attendant can be assigned to two bookings on different dates") {
    for
      ctx        <- makeCtx
      bookingId1  = BookingId.generate
      bookingId2  = BookingId.generate
      id         <- ctx.svc.createAttendant(createRequest())
      _          <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId1, providerId, List(id)))
      _          <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId2, providerId, List(id)))
      assigned1  <- ctx.attendantRepo.findByBooking(bookingId1)
      assigned2  <- ctx.attendantRepo.findByBooking(bookingId2)
    yield
      assertEquals(assigned1.size, 1)
      assertEquals(assigned2.size, 1)
  }

  test("assignAttendants raises AttendantNotFound for unknown attendant id") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      bogus      = AttendantId.generate
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, List(bogus))).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.AttendantNotFound => ()
        case other                               => fail(s"Expected AttendantNotFound, got $other")
      }
  }

  test("assignAttendants raises AttendantInactive for deactivated attendant") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      id        <- ctx.svc.createAttendant(createRequest())
      _         <- ctx.svc.deactivateAttendant(id, providerId)
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, List(id))).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.AttendantInactive => ()
        case other                               => fail(s"Expected AttendantInactive, got $other")
      }
  }

  test("assignAttendants raises Forbidden when provider does not own the attendant") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      id        <- ctx.svc.createAttendant(createRequest(providerId = providerId))
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId2, List(id))).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.Forbidden => ()
        case other                       => fail(s"Expected Forbidden, got $other")
      }
  }
