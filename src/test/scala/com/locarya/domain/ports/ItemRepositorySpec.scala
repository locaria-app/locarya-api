package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryItemRepository
import munit.CatsEffectSuite

class ItemRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ItemRepository[IO]] =
    InMemoryItemRepository.make[IO]

  private val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

  private def makeItem(
    providerId: ProviderId = ProviderId.generate,
    name: String = "Cadeira Tiffany"
  ): Item =
    Item.create(
      id = ItemId.generate,
      providerId = providerId,
      name = name,
      description = "Cadeira para eventos",
      dailyRate = dailyRate,
      stock = 10,
      attendantRequirement = AttendantRequirement.Optional
    ).toOption.get

  test("create stores item and findById retrieves it") {
    for
      repo   <- makeRepo
      item    = makeItem()
      stored <- repo.create(item)
      found  <- repo.findById(item.id)
    yield
      assertEquals(stored, item)
      assertEquals(found, Some(item))
  }

  test("findById returns None for missing item") {
    for
      repo  <- makeRepo
      found <- repo.findById(ItemId.generate)
    yield assertEquals(found, None)
  }

  test("findByProviderId returns only items belonging to that provider") {
    val pid1 = ProviderId.generate
    val pid2 = ProviderId.generate
    for
      repo  <- makeRepo
      item1  = makeItem(pid1, "Mesa Redonda")
      item2  = makeItem(pid1, "Toalha Branca")
      item3  = makeItem(pid2, "Cadeira Bistro")
      _     <- repo.create(item1)
      _     <- repo.create(item2)
      _     <- repo.create(item3)
      items <- repo.findByProviderId(pid1)
    yield
      assertEquals(items.map(_.id).toSet, Set(item1.id, item2.id))
      assert(!items.exists(_.id == item3.id))
  }

  test("findByProviderId returns empty list for unknown provider") {
    for
      repo  <- makeRepo
      items <- repo.findByProviderId(ProviderId.generate)
    yield assertEquals(items, Nil)
  }

  test("update overwrites item fields") {
    for
      repo    <- makeRepo
      item     = makeItem()
      _       <- repo.create(item)
      updated  = Item.create(item.id, item.providerId, item.name, item.description, item.dailyRate, 20, item.attendantRequirement).toOption.get
      saved   <- repo.update(updated)
      found   <- repo.findById(item.id)
    yield
      assertEquals(saved.stock, 20)
      assertEquals(found.map(_.stock), Some(20))
  }

  test("create with duplicate id raises in F") {
    for
      repo   <- makeRepo
      item    = makeItem()
      _      <- repo.create(item)
      result <- repo.create(item).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }
