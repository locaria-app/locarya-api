package com.locarya.adapters.persistence

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.domain.ports.CustomerRepository
import doobie.Transactor
import munit.CatsEffectSuite

class CustomerRepositoryLiveSpec extends CatsEffectSuite:

  private val testDbUrl      = "jdbc:postgresql://localhost:5432/locarya"
  private val testDbUser     = "locarya"
  private val testDbPassword = "locarya_dev_password"

  // Compile smoke test — no SQL is executed, no DB connection is established.
  // Behavioral correctness is covered by CustomerRepositorySpec (InMemoryCustomerRepository).
  // Full integration tests with a real DB are deferred per ADR 0007.
  test("make compiles and produces a CustomerRepository[IO]") {
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      testDbUrl,
      testDbUser,
      testDbPassword,
      None
    )
    IO {
      val _: CustomerRepository[IO] = CustomerRepositoryLive.make[IO](xa)
    }
  }

  test("findByIds returns Map.empty for empty input without a DB hit".ignore) {
    // Requires Docker Compose: docker-compose up -d. Deferred per ADR 0007.
    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      val repo = CustomerRepositoryLive.make[IO](xa)
      for
        result <- repo.findByIds(List.empty)
        _      <- IO(assert(result == Map.empty))
      yield ()
    }
  }
