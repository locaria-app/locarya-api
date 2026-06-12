package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryBookingRepository
import munit.CatsEffectSuite
import java.time.LocalDate

class BookingRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[BookingRepository[IO]] =
    InMemoryBookingRepository.make[IO]

  private val amount = Money.fromAmount(BigDecimal("200.00")).toOption.get

  private def makeBooking(
    providerId: ProviderId = ProviderId.generate,
    startDate: LocalDate = LocalDate.of(2026, 6, 1),
    endDate: LocalDate = LocalDate.of(2026, 6, 5),
    status: BookingStatus = BookingStatus.Pending
  ): Booking =
    Booking.create(
      id = BookingId.generate,
      providerId = providerId,
      customerId = CustomerId.generate,
      items = List(BookedIndividualItem(ItemId.generate, 1)),
      startDate = startDate,
      endDate = endDate,
      totalAmount = amount,
      status = status
    ).toOption.get

  test("create stores booking and findById retrieves it") {
    for
      repo   <- makeRepo
      b       = makeBooking()
      stored <- repo.create(b)
      found  <- repo.findById(b.id)
    yield
      assertEquals(stored, b)
      assertEquals(found, Some(b))
  }

  test("findById returns None for missing booking") {
    for
      repo  <- makeRepo
      found <- repo.findById(BookingId.generate)
    yield assertEquals(found, None)
  }

  test("findByProvider returns only bookings for that provider") {
    val pid1 = ProviderId.generate
    val pid2 = ProviderId.generate
    for
      repo     <- makeRepo
      b1        = makeBooking(pid1)
      b2        = makeBooking(pid1)
      b3        = makeBooking(pid2)
      _        <- repo.create(b1)
      _        <- repo.create(b2)
      _        <- repo.create(b3)
      bookings <- repo.findByProvider(pid1)
    yield
      assertEquals(bookings.map(_.id).toSet, Set(b1.id, b2.id))
      assert(!bookings.exists(_.id == b3.id))
  }

  test("findByProvider returns empty list for unknown provider") {
    for
      repo     <- makeRepo
      bookings <- repo.findByProvider(ProviderId.generate)
    yield assertEquals(bookings, Nil)
  }

  test("findByStatus filters by booking status") {
    for
      repo     <- makeRepo
      pending   = makeBooking(status = BookingStatus.Pending)
      confirmed = makeBooking(status = BookingStatus.Confirmed)
      cancelled = makeBooking(status = BookingStatus.Cancelled)
      _        <- repo.create(pending)
      _        <- repo.create(confirmed)
      _        <- repo.create(cancelled)
      results  <- repo.findByStatus(BookingStatus.Pending)
    yield
      assertEquals(results.map(_.id).toSet, Set(pending.id))
  }

  test("findByDateRange returns bookings overlapping the range") {
    // booking Jun 1-5 overlaps with query Jun 3-8
    for
      repo     <- makeRepo
      b1        = makeBooking(startDate = LocalDate.of(2026, 6, 1), endDate = LocalDate.of(2026, 6, 5))
      b2        = makeBooking(startDate = LocalDate.of(2026, 6, 10), endDate = LocalDate.of(2026, 6, 15))
      _        <- repo.create(b1)
      _        <- repo.create(b2)
      results  <- repo.findByDateRange(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 8))
    yield
      assertEquals(results.map(_.id), List(b1.id))
  }

  test("findByDateRange uses inclusive bounds on both ends") {
    // booking exactly on the boundary dates must be included
    for
      repo     <- makeRepo
      b         = makeBooking(startDate = LocalDate.of(2026, 7, 1), endDate = LocalDate.of(2026, 7, 1))
      _        <- repo.create(b)
      results  <- repo.findByDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1))
    yield assertEquals(results.map(_.id), List(b.id))
  }

  test("findByDateRange returns empty list when no bookings overlap") {
    for
      repo     <- makeRepo
      b         = makeBooking(startDate = LocalDate.of(2026, 6, 1), endDate = LocalDate.of(2026, 6, 5))
      _        <- repo.create(b)
      results  <- repo.findByDateRange(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 15))
    yield assertEquals(results, Nil)
  }

  test("update overwrites booking fields") {
    for
      repo    <- makeRepo
      b        = makeBooking()
      _       <- repo.create(b)
      updated  = Booking.create(b.id, b.providerId, b.customerId, b.items, b.startDate, b.endDate, b.totalAmount, BookingStatus.Confirmed).toOption.get
      saved   <- repo.update(updated)
      found   <- repo.findById(b.id)
    yield
      assertEquals(saved.status, BookingStatus.Confirmed)
      assertEquals(found.map(_.status), Some(BookingStatus.Confirmed))
  }

  test("create with duplicate id raises in F") {
    for
      repo   <- makeRepo
      b       = makeBooking()
      _      <- repo.create(b)
      result <- repo.create(b).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }

  test("existsForItem returns false when no booking references the item") {
    for
      repo   <- makeRepo
      result <- repo.existsForItem(ItemId.generate)
    yield assertEquals(result, false)
  }

  test("existsForItem returns true when a booking references the item") {
    val itemId = ItemId.generate
    for
      repo    <- makeRepo
      booking  = Booking.create(
                   id = BookingId.generate,
                   providerId = ProviderId.generate,
                   customerId = CustomerId.generate,
                   items = List(BookedIndividualItem(itemId, 1)),
                   startDate = LocalDate.of(2026, 6, 1),
                   endDate = LocalDate.of(2026, 6, 5),
                   totalAmount = amount
                 ).toOption.get
      _       <- repo.create(booking)
      result  <- repo.existsForItem(itemId)
    yield assertEquals(result, true)
  }

  test("existsForItem returns false when booking references a different item") {
    val targetId = ItemId.generate
    val otherId  = ItemId.generate
    for
      repo    <- makeRepo
      booking  = Booking.create(
                   id = BookingId.generate,
                   providerId = ProviderId.generate,
                   customerId = CustomerId.generate,
                   items = List(BookedIndividualItem(otherId, 1)),
                   startDate = LocalDate.of(2026, 6, 1),
                   endDate = LocalDate.of(2026, 6, 5),
                   totalAmount = amount
                 ).toOption.get
      _       <- repo.create(booking)
      result  <- repo.existsForItem(targetId)
    yield assertEquals(result, false)
  }
