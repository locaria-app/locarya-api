package com.locarya.domain.models

import munit.FunSuite

class ItemSpec extends FunSuite {

  test("create Item with valid data succeeds") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "Drone DJI Mini",
      description = "Compact drone for aerial photography",
      dailyRate = dailyRate,
      stock = 5,
      requiresMonitor = true
    )

    assert(result.isRight, "Should create Item with valid data")
    result.foreach { item =>
      assertEquals(item.id, itemId)
      assertEquals(item.providerId, providerId)
      assertEquals(item.name, "Drone DJI Mini")
      assertEquals(item.description, "Compact drone for aerial photography")
      assertEquals(item.dailyRate, dailyRate)
      assertEquals(item.stock, 5)
      assertEquals(item.requiresMonitor, true)
    }
  }

  test("create Item with empty name fails") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "",
      description = "Some description",
      dailyRate = dailyRate,
      stock = 5,
      requiresMonitor = false
    )

    assert(result.isLeft, "Should fail to create Item with empty name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidItem], "Should return InvalidItem error")
    }
  }

  test("create Item with whitespace-only name fails") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "   ",
      description = "Some description",
      dailyRate = dailyRate,
      stock = 5,
      requiresMonitor = false
    )

    assert(result.isLeft, "Should fail to create Item with whitespace-only name")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidItem], "Should return InvalidItem error")
    }
  }

  test("create Item with negative stock fails") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "Camera",
      description = "Professional camera",
      dailyRate = dailyRate,
      stock = -1,
      requiresMonitor = false
    )

    assert(result.isLeft, "Should fail to create Item with negative stock")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidItem], "Should return InvalidItem error")
    }
  }

  test("create Item with zero stock succeeds") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "Camera",
      description = "Professional camera",
      dailyRate = dailyRate,
      stock = 0,
      requiresMonitor = false
    )

    assert(result.isRight, "Should create Item with zero stock")
    result.foreach { item =>
      assertEquals(item.stock, 0)
    }
  }

  test("create Item trims name and description") {
    val itemId = ItemId.generate
    val providerId = ProviderId.generate
    val dailyRate = Money.fromAmount(BigDecimal("50.00")).toOption.get

    val result = Item.create(
      id = itemId,
      providerId = providerId,
      name = "  Camera  ",
      description = "  Professional camera  ",
      dailyRate = dailyRate,
      stock = 5,
      requiresMonitor = false
    )

    assert(result.isRight, "Should create Item and trim whitespace")
    result.foreach { item =>
      assertEquals(item.name, "Camera")
      assertEquals(item.description, "Professional camera")
    }
  }
}
