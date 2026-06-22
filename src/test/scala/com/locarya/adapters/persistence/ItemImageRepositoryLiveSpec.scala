package com.locarya.adapters.persistence

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ItemImageRepository
import doobie.Transactor
import munit.CatsEffectSuite

class ItemImageRepositoryLiveSpec extends CatsEffectSuite:

  private val testDbUrl      = "jdbc:postgresql://localhost:5432/locarya"
  private val testDbUser     = "locarya"
  private val testDbPassword = "locarya_dev_password"

  // Compile smoke test — no SQL is executed, no DB connection is established.
  // Behavioral correctness is covered by ItemImageRepositorySpec (InMemoryItemImageRepository).
  // Full integration tests with a real DB are deferred per ADR 0007.
  test("make compiles and produces an ItemImageRepository[IO]") {
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      testDbUrl,
      testDbUser,
      testDbPassword,
      None
    )
    IO {
      val _: ItemImageRepository[IO] = ItemImageRepositoryLive.make[IO](xa)
    }
  }

  test("replaceImages removes old images and stores new ones atomically".ignore) {
    // Requires Docker Compose: docker-compose up -d. Deferred per ADR 0007.
    Database.transactor[IO](testDbUrl, testDbUser, testDbPassword).use { xa =>
      val repo   = ItemImageRepositoryLive.make[IO](xa)
      val itemId = ItemId.generate
      val old    = ItemImage.create(itemId, List("https://example.com/a.jpg")).toOption.get
      val next   = ItemImage.create(itemId, List("https://example.com/b.jpg")).toOption.get
      for
        _     <- old.traverse_(repo.create)
        _     <- repo.replaceImages(itemId, next)
        found <- repo.findByItemId(itemId)
      yield assertEquals(found.map(_.imageUrl.value).toSet, Set("https://example.com/b.jpg"))
    }
  }
