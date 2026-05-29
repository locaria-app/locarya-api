package com.locarya.core.domain

import munit.FunSuite

class ComboSpec extends FunSuite {

  test("create Combo with valid data succeeds") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId1 = ItemId.generate
    val itemId2 = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val items = List(
      ComboItemDefinition(itemId1, 1),
      ComboItemDefinition(itemId2, 2)
    )

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "Party Kit",
      description = "Complete party equipment",
      dailyRate = dailyRate,
      items = items,
      attendantRequirement = AttendantRequirement.Required
    )

    assert(result.isRight, "Should create Combo with valid data")
    result.foreach { combo =>
      assertEquals(combo.id, comboId)
      assertEquals(combo.providerId, providerId)
      assertEquals(combo.name, "Party Kit")
      assertEquals(combo.description, "Complete party equipment")
      assertEquals(combo.dailyRate, dailyRate)
      assertEquals(combo.items.size, 2)
      assertEquals(combo.attendantRequirement, AttendantRequirement.Required)
    }
  }

  test("create Combo with empty name fails") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "",
      description = "Some description",
      dailyRate = dailyRate,
      items = List(ComboItemDefinition(itemId, 1)),
      attendantRequirement = AttendantRequirement.Optional
    )

    assert(result.isLeft, "Should fail to create Combo with empty name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCombo], "Should return InvalidCombo error")
    }
  }

  test("create Combo with whitespace-only name fails") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "   ",
      description = "Some description",
      dailyRate = dailyRate,
      items = List(ComboItemDefinition(itemId, 1)),
      attendantRequirement = AttendantRequirement.Optional
    )

    assert(result.isLeft, "Should fail to create Combo with whitespace-only name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCombo], "Should return InvalidCombo error")
    }
  }

  test("create Combo with empty items list fails") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "Empty Combo",
      description = "Should fail",
      dailyRate = dailyRate,
      items = List.empty,
      attendantRequirement = AttendantRequirement.Optional
    )

    assert(result.isLeft, "Should fail to create Combo with empty items list")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCombo], "Should return InvalidCombo error")
    }
  }

  test("create Combo with zero quantity item fails") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "Invalid Combo",
      description = "Has zero quantity",
      dailyRate = dailyRate,
      items = List(ComboItemDefinition(itemId, 0)),
      attendantRequirement = AttendantRequirement.Optional
    )

    assert(result.isLeft, "Should fail to create Combo with zero quantity item")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCombo], "Should return InvalidCombo error")
    }
  }

  test("create Combo with negative quantity item fails") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "Invalid Combo",
      description = "Has negative quantity",
      dailyRate = dailyRate,
      items = List(ComboItemDefinition(itemId, -1)),
      attendantRequirement = AttendantRequirement.Optional
    )

    assert(result.isLeft, "Should fail to create Combo with negative quantity item")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidCombo], "Should return InvalidCombo error")
    }
  }

  test("create Combo trims name and description") {
    val comboId = ComboId.generate
    val providerId = ProviderId.generate
    val itemId = ItemId.generate
    val dailyRate = Money.fromAmount(BigDecimal("150.00")).toOption.get

    val result = Combo.create(
      id = comboId,
      providerId = providerId,
      name = "  Party Kit  ",
      description = "  Complete party equipment  ",
      dailyRate = dailyRate,
      items = List(ComboItemDefinition(itemId, 1)),
      attendantRequirement = AttendantRequirement.Required
    )

    assert(result.isRight, "Should create Combo and trim whitespace")
    result.foreach { combo =>
      assertEquals(combo.name, "Party Kit")
      assertEquals(combo.description, "Complete party equipment")
    }
  }
}
