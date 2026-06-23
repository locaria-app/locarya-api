package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
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

  test("assignToBooking and findByBooking return assigned attendants") {
    val bookingId = BookingId.generate
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      _         <- repo.assignToBooking(bookingId, attendant.id)
      found     <- repo.findByBooking(bookingId)
    yield assertEquals(found.map(_.id), List(attendant.id))
  }

  test("removeFromBooking unassigns attendant from booking") {
    val bookingId = BookingId.generate
    for
      repo      <- makeRepo
      attendant  = makeAttendant()
      _         <- repo.create(attendant)
      _         <- repo.assignToBooking(bookingId, attendant.id)
      _         <- repo.removeFromBooking(bookingId, attendant.id)
      found     <- repo.findByBooking(bookingId)
    yield assertEquals(found, Nil)
  }

  test("findByBooking returns empty list for booking with no attendants") {
    for
      repo  <- makeRepo
      found <- repo.findByBooking(BookingId.generate)
    yield assertEquals(found, Nil)
  }
