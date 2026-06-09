package com.locarya.domain.models

import munit.FunSuite
import java.util.UUID

class EntityIdSpec extends FunSuite {

  test("generate new ProviderId creates valid UUID-based ID") {
    val id = ProviderId.generate

    assert(id.value.nonEmpty, "Generated ID should not be empty")
    // Verify it's a valid UUID format
    UUID.fromString(id.value) // throws if invalid
  }

  test("create ProviderId from valid UUID string succeeds") {
    val uuid = UUID.randomUUID().toString
    val result = ProviderId.fromString(uuid)

    assert(result.isRight, "Should create ProviderId from valid UUID")
    result.foreach { id =>
      assertEquals(id.value, uuid)
    }
  }

  test("create ProviderId from invalid UUID string fails") {
    val result = ProviderId.fromString("not-a-uuid")

    assert(result.isLeft, "Should fail when UUID format is invalid")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidEntityId], "Should return InvalidEntityId error")
    }
  }

  test("all entity ID types can be generated") {
    val providerId = ProviderId.generate
    val customerId = CustomerId.generate
    val itemId = ItemId.generate
    val comboId = ComboId.generate
    val bookingId = BookingId.generate
    val attendantId = AttendantId.generate
    val paymentId = PaymentId.generate

    // All should be valid UUIDs
    UUID.fromString(providerId.value)
    UUID.fromString(customerId.value)
    UUID.fromString(itemId.value)
    UUID.fromString(comboId.value)
    UUID.fromString(bookingId.value)
    UUID.fromString(attendantId.value)
    UUID.fromString(paymentId.value)
  }

  test("all entity ID types can be parsed from valid UUID strings") {
    val uuid = UUID.randomUUID().toString

    assert(ProviderId.fromString(uuid).isRight)
    assert(CustomerId.fromString(uuid).isRight)
    assert(ItemId.fromString(uuid).isRight)
    assert(ComboId.fromString(uuid).isRight)
    assert(BookingId.fromString(uuid).isRight)
    assert(AttendantId.fromString(uuid).isRight)
    assert(PaymentId.fromString(uuid).isRight)
  }
}
