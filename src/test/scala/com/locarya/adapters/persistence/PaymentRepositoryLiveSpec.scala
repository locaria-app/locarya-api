package com.locarya.adapters.persistence

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.PaymentRepository
import com.locarya.helpers.InMemoryPaymentRepository
import doobie.Transactor
import java.time.Instant
import munit.CatsEffectSuite

class PaymentRepositoryLiveSpec extends CatsEffectSuite:

  private val testDbUrl      = "jdbc:postgresql://localhost:5432/locarya"
  private val testDbUser     = "locarya"
  private val testDbPassword = "locarya_dev_password"

  // Compile smoke test — no SQL is executed, no DB connection is established.
  // Behavioral correctness is covered by PaymentRepositorySpec (InMemoryPaymentRepository).
  // Full integration tests with a real DB are deferred per ADR 0007.
  test("make compiles and produces a PaymentRepository[IO]") {
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      testDbUrl,
      testDbUser,
      testDbPassword,
      None
    )
    IO {
      val _: PaymentRepository[IO] = PaymentRepositoryLive.make[IO](xa)
    }
  }

  private def mkPayment(
    paymentId: PaymentId,
    bookingId: BookingId,
    note: Option[String],
    now: Instant = Instant.now()
  ): IO[Payment] =
    IO.fromEither(
      Payment
        .create(paymentId, bookingId, BigDecimal("100.00"), PaymentMethod.PixManual, note, now)
        .left
        .map(e => new RuntimeException(e.message))
    )

  test("update preserves note field change — V7 column (InMemoryPaymentRepository oracle)") {
    val bookingId = BookingId.fromString("00000000-0000-0000-0000-000000000001").getOrElse(???)
    val paymentId = PaymentId.fromString("00000000-0000-0000-0000-000000000002").getOrElse(???)
    val now = Instant.now()
    for
      repo    <- InMemoryPaymentRepository.make[IO]
      p       <- mkPayment(paymentId, bookingId, Some("original note"), now)
      _       <- repo.create(p)
      updated <- mkPayment(paymentId, bookingId, Some("updated note"), now)
      _       <- repo.update(updated)
      found   <- repo.findById(paymentId)
      _       <- IO(assertEquals(found.flatMap(_.note), Some("updated note")))
    yield ()
  }

  test("update can clear note field to None — V7 column (InMemoryPaymentRepository oracle)") {
    val bookingId = BookingId.fromString("00000000-0000-0000-0000-000000000003").getOrElse(???)
    val paymentId = PaymentId.fromString("00000000-0000-0000-0000-000000000004").getOrElse(???)
    val now = Instant.now()
    for
      repo    <- InMemoryPaymentRepository.make[IO]
      p       <- mkPayment(paymentId, bookingId, Some("to be removed"), now)
      _       <- repo.create(p)
      cleared <- mkPayment(paymentId, bookingId, None, now)
      _       <- repo.update(cleared)
      found   <- repo.findById(paymentId)
      _       <- IO(assertEquals(found.flatMap(_.note), None))
    yield ()
  }

  test("findByBooking returns all payments for that booking (InMemoryPaymentRepository oracle)") {
    val bookingId  = BookingId.fromString("00000000-0000-0000-0000-000000000005").getOrElse(???)
    val paymentId1 = PaymentId.fromString("00000000-0000-0000-0000-000000000006").getOrElse(???)
    val paymentId2 = PaymentId.fromString("00000000-0000-0000-0000-000000000007").getOrElse(???)
    for
      repo <- InMemoryPaymentRepository.make[IO]
      p1   <- mkPayment(paymentId1, bookingId, None)
      p2   <- mkPayment(paymentId2, bookingId, None)
      _    <- repo.create(p1)
      _    <- repo.create(p2)
      list <- repo.findByBooking(bookingId)
      _    <- IO(assertEquals(list.length, 2))
    yield ()
  }

  test("create raises on duplicate payment id (InMemoryPaymentRepository oracle)") {
    val bookingId = BookingId.fromString("00000000-0000-0000-0000-000000000008").getOrElse(???)
    val paymentId = PaymentId.fromString("00000000-0000-0000-0000-000000000009").getOrElse(???)
    for
      repo   <- InMemoryPaymentRepository.make[IO]
      p      <- mkPayment(paymentId, bookingId, None)
      _      <- repo.create(p)
      result <- repo.create(p).attempt
      _      <- IO(assert(result.isLeft, "expected error on duplicate payment id"))
    yield ()
  }

  test("create and findById round-trip preserves note field (InMemoryPaymentRepository oracle)") {
    val bookingId = BookingId.fromString("00000000-0000-0000-0000-00000000000a").getOrElse(???)
    val paymentId = PaymentId.fromString("00000000-0000-0000-0000-00000000000b").getOrElse(???)
    for
      repo   <- InMemoryPaymentRepository.make[IO]
      p      <- mkPayment(paymentId, bookingId, Some("test note"))
      stored <- repo.create(p)
      found  <- repo.findById(paymentId)
      _      <- IO(assertEquals(found, Some(stored)))
    yield ()
  }

  test("live repository note round-trip (requires DB, deferred per ADR 0007)".ignore) {
    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      val repo      = PaymentRepositoryLive.make[IO](xa)
      val bookingId = BookingId.fromString("00000000-0000-0000-0000-000000000010").getOrElse(???)
      for
        p      <- IO.fromEither(
                    Payment
                      .create(PaymentId.generate, bookingId, BigDecimal("150.00"), PaymentMethod.PixManual, Some("live note"), Instant.now())
                      .left
                      .map(e => new RuntimeException(e.message))
                  )
        stored <- repo.create(p)
        found  <- repo.findById(stored.id)
        _      <- IO(assertEquals(found.flatMap(_.note), Some("live note")))
      yield ()
    }
  }
