package com.locarya.domain.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.locarya.domain.models.*
import com.locarya.helpers.{
  InMemoryBookingRepository,
  InMemoryComboRepository,
  InMemoryItemRepository
}
import java.time.LocalDate
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

class AvailabilityServicePropertySpec extends ScalaCheckSuite:

  private given Logger[IO] = NoOpLogger[IO]

  private val providerId = ProviderId.generate
  private val price      = Money.fromAmount(BigDecimal("50.00")).toOption.get

  // Stocks bounded to keep the property fast; signs allow both available and depleted paths.
  private val stockGen: Gen[Int]      = Gen.choose(1, 20)
  private val consumedGen: Gen[Int]   = Gen.choose(0, 20)
  private val requestedGen: Gen[Int]  = Gen.choose(1, 20)
  // A date range covering a year so we get variety, but stay deterministic.
  private val dateGen: Gen[LocalDate] =
    Gen.choose(0L, 364L).map(d => LocalDate.of(2026, 1, 1).plusDays(d))

  property("availability == (stock - consumed >= requested) for individual Items") {
    forAll(stockGen, consumedGen, requestedGen, dateGen) {
      (stock: Int, consumed: Int, requested: Int, date: LocalDate) =>
        val program = for
          itemRepo    <- InMemoryItemRepository.make[IO]
          comboRepo   <- InMemoryComboRepository.make[IO]
          bookingRepo <- InMemoryBookingRepository.make[IO]
          svc          = AvailabilityServiceImpl[IO](itemRepo, comboRepo, bookingRepo)
          item         = Item.create(
                           ItemId.generate, providerId, "Cadeira", "",
                           price, stock, AttendantRequirement.Optional
                         ).toOption.get
          _           <- itemRepo.create(item)
          // Seed a single confirmed booking that consumes `consumed` units on the date.
          seedBooking  = Booking.create(
                           BookingId.generate, providerId, CustomerId.generate,
                           List(BookedIndividualItem(item.id, consumed.max(1))),
                           date, date, price, BookingStatus.Confirmed
                         ).toOption.get
          _           <- if consumed > 0 then bookingRepo.create(seedBooking).void else IO.unit
          result      <- svc.checkAvailability(List((item.id, requested)), date, None)
        yield result.available == (stock - consumed >= requested)

        program.unsafeRunSync()
    }
  }
