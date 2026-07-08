package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.models.BookingLineRef
import com.locarya.helpers.InMemoryAttendantRepository
import munit.CatsEffectSuite

class AttendantRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[AttendantRepository[IO]] =
    InMemoryAttendantRepository.make[IO]

  private def makeAttendant(
    providerId: ProviderId = ProviderId.generate,
    name: String = "João Silva",
    phone: String = "11999990000",
    isActive: Boolean = true
  ): Attendant =
    Attendant.create(AttendantId.generate, providerId, name, phone, isActive).toOption.get

  test("create stores attendant and findById retrieves it") {
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      stored    <- repo.create(attendant)
      found     <- repo.findById(attendant.id)
    yield
      assertEquals(stored, attendant)
      assertEquals(found, Some(attendant))
  }

  test("findById returns None for missing attendant") {
    for
      repo  <- makeRepo
      found <- repo.findById(AttendantId.generate)
    yield assertEquals(found, None)
  }

  test("update overwrites attendant fields") {
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      updated    = Attendant.create(attendant.id, attendant.providerId, "Maria Souza", "11988880000", attendant.isActive).toOption.get
      saved     <- repo.update(updated)
      found     <- repo.findById(attendant.id)
    yield
      assertEquals(saved.name, "Maria Souza")
      assertEquals(found.map(_.name), Some("Maria Souza"))
  }

  test("create with duplicate id raises in F") {
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      result    <- repo.create(attendant).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }

  test("findByProvider returns attendants for that provider only") {
    val pid1 = ProviderId.generate
    val pid2 = ProviderId.generate
    for
      repo <- makeRepo
      a1    = makeAttendant(providerId = pid1)
      a2    = makeAttendant(providerId = pid1)
      a3    = makeAttendant(providerId = pid2)
      _    <- repo.create(a1)
      _    <- repo.create(a2)
      _    <- repo.create(a3)
      list <- repo.findByProvider(pid1)
    yield assertEquals(list.map(_.id).toSet, Set(a1.id, a2.id))
  }

  test("findActiveByProvider returns only active attendants for that provider") {
    val pid = ProviderId.generate
    for
      repo    <- makeRepo
      active   = makeAttendant(providerId = pid, isActive = true)
      inactive = makeAttendant(providerId = pid, isActive = false)
      _       <- repo.create(active)
      _       <- repo.create(inactive)
      list    <- repo.findActiveByProvider(pid)
    yield assertEquals(list.map(_.id), List(active.id))
  }

  test("assignToBookingLine and findByBookingGrouped return attendant under correct line") {
    val bookingId = BookingId.generate
    val itemId    = ItemId.generate
    val lineRef   = BookingLineRef.IndividualLine(itemId)
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      _         <- repo.assignToBookingLine(bookingId, lineRef, attendant.id)
      grouped   <- repo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(lineRef).map(_.toList), Some(List(attendant.id)))
  }

  test("assignToBookingLine with ComboLine groups under combo key") {
    val bookingId = BookingId.generate
    val comboId   = ComboId.generate
    val lineRef   = BookingLineRef.ComboLine(comboId)
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      _         <- repo.assignToBookingLine(bookingId, lineRef, attendant.id)
      grouped   <- repo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(lineRef).map(_.toList), Some(List(attendant.id)))
  }

  test("removeFromBookingLine clears only that line's attendant") {
    val bookingId  = BookingId.generate
    val itemId1    = ItemId.generate
    val itemId2    = ItemId.generate
    val line1      = BookingLineRef.IndividualLine(itemId1)
    val line2      = BookingLineRef.IndividualLine(itemId2)
    for
      repo       <- makeRepo
      attendant1  = makeAttendant()
      attendant2  = makeAttendant()
      _          <- repo.create(attendant1)
      _          <- repo.create(attendant2)
      _          <- repo.assignToBookingLine(bookingId, line1, attendant1.id)
      _          <- repo.assignToBookingLine(bookingId, line2, attendant2.id)
      _          <- repo.removeFromBookingLine(bookingId, line1, attendant1.id)
      grouped    <- repo.findByBookingGrouped(bookingId)
    yield
      assert(grouped.get(line1).forall(_.isEmpty), "line1 should be empty after removal")
      assertEquals(grouped.get(line2).map(_.toList), Some(List(attendant2.id)))
  }

  test("findByBookingGrouped returns empty map for booking with no assignments") {
    for
      repo    <- makeRepo
      grouped <- repo.findByBookingGrouped(BookingId.generate)
    yield assertEquals(grouped, Map.empty)
  }

  test("two lines on same booking each track their own attendant independently") {
    val bookingId = BookingId.generate
    val line1     = BookingLineRef.IndividualLine(ItemId.generate)
    val line2     = BookingLineRef.ComboLine(ComboId.generate)
    for
      repo       <- makeRepo
      attendant1  = makeAttendant()
      attendant2  = makeAttendant()
      _          <- repo.create(attendant1)
      _          <- repo.create(attendant2)
      _          <- repo.assignToBookingLine(bookingId, line1, attendant1.id)
      _          <- repo.assignToBookingLine(bookingId, line2, attendant2.id)
      grouped    <- repo.findByBookingGrouped(bookingId)
    yield
      assertEquals(grouped.get(line1).map(_.toList), Some(List(attendant1.id)))
      assertEquals(grouped.get(line2).map(_.toList), Some(List(attendant2.id)))
  }
