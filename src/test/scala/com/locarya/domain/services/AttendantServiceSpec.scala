package com.locarya.domain.services

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.models.BookingLineRef
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

  test("assignAttendants stores join under line ref and findByBookingGrouped returns it") {
    val bookingId = BookingId.generate
    val lineRef   = BookingLineRef.IndividualLine(ItemId.generate)
    for
      ctx      <- makeCtx
      id       <- ctx.svc.createAttendant(createRequest())
      _        <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, lineRef, List(id)))
      grouped  <- ctx.attendantRepo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(lineRef).map(_.size), Some(1))
      assertEquals(grouped.get(lineRef).flatMap(_.headOption), Some(id))
  }

  test("assignAttendants to combo line stores under combo key") {
    val bookingId = BookingId.generate
    val lineRef   = BookingLineRef.ComboLine(ComboId.generate)
    for
      ctx      <- makeCtx
      id       <- ctx.svc.createAttendant(createRequest())
      _        <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, lineRef, List(id)))
      grouped  <- ctx.attendantRepo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(lineRef).map(_.size), Some(1))
  }

  test("assignAttendants to two lines on same booking tracks them independently") {
    val bookingId = BookingId.generate
    val line1     = BookingLineRef.IndividualLine(ItemId.generate)
    val line2     = BookingLineRef.ComboLine(ComboId.generate)
    for
      ctx      <- makeCtx
      id1      <- ctx.svc.createAttendant(createRequest(name = "Monitor A"))
      id2      <- ctx.svc.createAttendant(createRequest(name = "Monitor B"))
      _        <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, line1, List(id1)))
      _        <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, line2, List(id2)))
      grouped  <- ctx.attendantRepo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(line1).flatMap(_.headOption), Some(id1))
      assertEquals(grouped.get(line2).flatMap(_.headOption), Some(id2))
  }

  test("assignAttendants — same attendant can be assigned to two bookings") {
    val line = BookingLineRef.IndividualLine(ItemId.generate)
    for
      ctx        <- makeCtx
      bookingId1  = BookingId.generate
      bookingId2  = BookingId.generate
      id         <- ctx.svc.createAttendant(createRequest())
      _          <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId1, providerId, line, List(id)))
      _          <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId2, providerId, line, List(id)))
      grouped1   <- ctx.attendantRepo.findByBookingGrouped(bookingId1)
      grouped2   <- ctx.attendantRepo.findByBookingGrouped(bookingId2)
    yield
      assertEquals(grouped1.get(line).map(_.size), Some(1))
      assertEquals(grouped2.get(line).map(_.size), Some(1))
  }

  test("assignAttendants raises AttendantNotFound for unknown attendant id") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      lineRef    = BookingLineRef.IndividualLine(ItemId.generate)
      bogus      = AttendantId.generate
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, lineRef, List(bogus))).attempt
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
      lineRef    = BookingLineRef.IndividualLine(ItemId.generate)
      id        <- ctx.svc.createAttendant(createRequest())
      _         <- ctx.svc.deactivateAttendant(id, providerId)
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, lineRef, List(id))).attempt
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
      lineRef    = BookingLineRef.IndividualLine(ItemId.generate)
      id        <- ctx.svc.createAttendant(createRequest(providerId = providerId))
      result    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId2, lineRef, List(id))).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.Forbidden => ()
        case other                       => fail(s"Expected Forbidden, got $other")
      }
  }

  // ── removeAttendantFromLine ───────────────────────────────────────────────

  test("removeAttendantFromLine clears the attendant from that line only") {
    val bookingId = BookingId.generate
    val line1     = BookingLineRef.IndividualLine(ItemId.generate)
    val line2     = BookingLineRef.IndividualLine(ItemId.generate)
    for
      ctx  <- makeCtx
      id1  <- ctx.svc.createAttendant(createRequest(name = "Monitor A"))
      id2  <- ctx.svc.createAttendant(createRequest(name = "Monitor B"))
      _    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, line1, List(id1)))
      _    <- ctx.svc.assignAttendants(AssignAttendantsRequest(bookingId, providerId, line2, List(id2)))
      _    <- ctx.svc.removeAttendantFromLine(RemoveAttendantFromLineRequest(bookingId, providerId, line1, id1))
      g    <- ctx.attendantRepo.findByBookingGrouped(bookingId)
    yield
      assert(g.get(line1).forall(_.isEmpty), "line1 should be empty after removal")
      assertEquals(g.get(line2).flatMap(_.headOption), Some(id2))
  }

  test("removeAttendantFromLine raises AttendantNotFound for unknown attendant") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      lineRef    = BookingLineRef.IndividualLine(ItemId.generate)
      bogus      = AttendantId.generate
      result    <- ctx.svc.removeAttendantFromLine(RemoveAttendantFromLineRequest(bookingId, providerId, lineRef, bogus)).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.AttendantNotFound => ()
        case other                               => fail(s"Expected AttendantNotFound, got $other")
      }
  }

  test("removeAttendantFromLine raises Forbidden when provider does not own the attendant") {
    for
      ctx       <- makeCtx
      bookingId  = BookingId.generate
      lineRef    = BookingLineRef.IndividualLine(ItemId.generate)
      id        <- ctx.svc.createAttendant(createRequest(providerId = providerId))
      result    <- ctx.svc.removeAttendantFromLine(RemoveAttendantFromLineRequest(bookingId, providerId2, lineRef, id)).attempt
    yield
      assert(result.isLeft)
      result.left.foreach {
        case _: AttendantError.Forbidden => ()
        case other                       => fail(s"Expected Forbidden, got $other")
      }
  }
