package com.locarya.domain.ports

import cats.effect.IO
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryComboRepository
import munit.CatsEffectSuite

class ComboRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ComboRepository[IO]] =
    InMemoryComboRepository.make[IO]

  private val dailyRate = Money.fromAmount(BigDecimal("120.00")).toOption.get

  private def makeCombo(
    providerId: ProviderId = ProviderId.generate,
    items: List[ComboItemDefinition] = List(ComboItemDefinition(ItemId.generate, 2))
  ): Combo =
    Combo.create(
      id              = ComboId.generate,
      providerId      = providerId,
      name            = "Combo Festa",
      description     = "Combo para festas",
      dailyRate       = dailyRate,
      items           = items,
      requiresMonitor = false
    ).toOption.get

  test("create stores combo and findById retrieves it") {
    for
      repo   <- makeRepo
      combo   = makeCombo()
      stored <- repo.create(combo)
      found  <- repo.findById(combo.id)
    yield
      assertEquals(stored, combo)
      assertEquals(found, Some(combo))
  }

  test("findById returns None for missing combo") {
    for
      repo  <- makeRepo
      found <- repo.findById(ComboId.generate)
    yield assertEquals(found, None)
  }

  test("findItemsInCombo returns the items stored with the combo") {
    val item1 = ItemId.generate
    val item2 = ItemId.generate
    val comboItems = List(ComboItemDefinition(item1, 3), ComboItemDefinition(item2, 1))
    for
      repo  <- makeRepo
      combo  = makeCombo(items = comboItems)
      _     <- repo.create(combo)
      items <- repo.findItemsInCombo(combo.id)
    yield assertEquals(items, comboItems)
  }

  test("findItemsInCombo returns empty list for unknown combo") {
    for
      repo  <- makeRepo
      items <- repo.findItemsInCombo(ComboId.generate)
    yield assertEquals(items, Nil)
  }

  test("update overwrites combo fields") {
    for
      repo    <- makeRepo
      combo    = makeCombo()
      _       <- repo.create(combo)
      updated  = Combo.create(combo.id, combo.providerId, "Combo Premium", combo.description, combo.dailyRate, combo.items, combo.requiresMonitor).toOption.get
      saved   <- repo.update(updated)
      found   <- repo.findById(combo.id)
    yield
      assertEquals(saved.name, "Combo Premium")
      assertEquals(found.map(_.name), Some("Combo Premium"))
  }

  test("create with duplicate id raises in F") {
    for
      repo   <- makeRepo
      combo   = makeCombo()
      _      <- repo.create(combo)
      result <- repo.create(combo).attempt
    yield assert(result.isLeft, "Expected duplicate create to fail")
  }
