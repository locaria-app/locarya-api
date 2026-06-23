package com.locarya.adapters.persistence

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.BookingRepository
import doobie.Transactor
import munit.CatsEffectSuite

class BookingRepositoryLiveSpec extends CatsEffectSuite:

  private val testDbUrl      = "jdbc:postgresql://localhost:5432/locarya"
  private val testDbUser     = "locarya"
  private val testDbPassword = "locarya_dev_password"

  // Compile smoke test — no SQL is executed, no DB connection is established.
  // Behavioral correctness is covered by BookingRepositorySpec (InMemoryBookingRepository).
  // Full integration tests with a real DB are deferred per ADR 0007.
  test("make compiles and produces a BookingRepository[IO]") {
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      testDbUrl,
      testDbUser,
      testDbPassword,
      None
    )
    IO {
      val _: BookingRepository[IO] = BookingRepositoryLive.make[IO](xa)
    }
  }

  test("create and update handle booking_items atomically in the same transaction".ignore) {
    // Requires Docker Compose: docker-compose up -d. Deferred per ADR 0007.
    // Verifies that booking_items rows are written in the same transaction as bookings rows,
    // so a partial failure leaves no orphan items.
    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      val repo = BookingRepositoryLive.make[IO](xa)
      // Would require provider/customer/item fixture rows in DB for FK constraints.
      // Implementation detail verified in code review: create() and update() use
      // a single .transact(xa) wrapping both the bookings INSERT/UPDATE and
      // the booking_items DELETE/INSERT cycle.
      IO.unit
    }
  }
