package com.locarya.adapters.persistence

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.models.BookingLineRef
import com.locarya.domain.ports.AttendantRepository
import doobie.Transactor
import munit.CatsEffectSuite

class AttendantRepositoryLiveSpec extends CatsEffectSuite:

  private val testDbUrl      = "jdbc:postgresql://localhost:5432/locarya"
  private val testDbUser     = "locarya"
  private val testDbPassword = "locarya_dev_password"

  // Compile smoke test — no SQL is executed, no DB connection is established.
  // Behavioral correctness is covered by AttendantRepositorySpec (InMemoryAttendantRepository).
  // Full integration tests with a real DB are deferred per ADR 0007.
  test("make compiles and produces an AttendantRepository[IO]") {
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      testDbUrl,
      testDbUser,
      testDbPassword,
      None
    )
    IO {
      val _: AttendantRepository[IO] = AttendantRepositoryLive.make[IO](xa)
    }
  }

  test("assignToBookingLine and removeFromBookingLine reference booking_attendants table".ignore) {
    // Requires Docker Compose: docker-compose up -d. Deferred per ADR 0007.
    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      val repo        = AttendantRepositoryLive.make[IO](xa)
      val bookingId   = BookingId.generate
      val attendantId = AttendantId.generate
      val lineRef     = BookingLineRef.IndividualLine(ItemId.generate)
      for
        _ <- repo.assignToBookingLine(bookingId, lineRef, attendantId)
        _ <- repo.removeFromBookingLine(bookingId, lineRef, attendantId)
      yield ()
    }
  }
