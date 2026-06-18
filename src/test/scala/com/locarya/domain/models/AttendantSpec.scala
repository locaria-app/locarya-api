package com.locarya.domain.models

import munit.FunSuite

class AttendantSpec extends FunSuite:

  private val id         = AttendantId.generate
  private val providerId = ProviderId.generate

  test("create returns Right for valid name and phone") {
    val result = Attendant.create(id, providerId, "João Silva", "11999990000")
    assert(result.isRight)
    val a = result.toOption.get
    assertEquals(a.id, id)
    assertEquals(a.providerId, providerId)
    assertEquals(a.name, "João Silva")
    assertEquals(a.phone, "11999990000")
    assertEquals(a.isActive, true)
  }

  test("create trims whitespace from name") {
    val result = Attendant.create(id, providerId, "  João  ", "11999990000")
    assertEquals(result.map(_.name), Right("João"))
  }

  test("create returns Left(InvalidAttendant) for empty name") {
    val result = Attendant.create(id, providerId, "", "11999990000")
    result match
      case Left(InvalidAttendant(msg)) => assert(msg.nonEmpty)
      case other                       => fail(s"Expected Left(InvalidAttendant), got $other")
  }

  test("create returns Left(InvalidAttendant) for whitespace-only name") {
    val result = Attendant.create(id, providerId, "   ", "11999990000")
    assert(result.isLeft)
    result.left.foreach {
      case _: InvalidAttendant => ()
      case other               => fail(s"Expected InvalidAttendant, got $other")
    }
  }

  test("create defaults isActive to true") {
    val a = Attendant.create(id, providerId, "Maria", "11900000000").toOption.get
    assertEquals(a.isActive, true)
  }

  test("create with explicit isActive=false preserves that value") {
    val a = Attendant.create(id, providerId, "Maria", "11900000000", isActive = false).toOption.get
    assertEquals(a.isActive, false)
  }

  test("deactivate sets isActive to false") {
    val a       = Attendant.create(id, providerId, "Maria", "11900000000").toOption.get
    val inactive = a.deactivate
    assertEquals(inactive.isActive, false)
    assertEquals(inactive.name, a.name)
    assertEquals(inactive.id, a.id)
  }
