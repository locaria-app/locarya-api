package com.locarya.domain.models

import munit.FunSuite

class PasswordSpec extends FunSuite:

  test("valid password with uppercase, lowercase and digit is accepted") {
    assert(Password.fromString("Securepass1").isRight)
  }

  test("password shorter than 8 chars is rejected") {
    assertEquals(Password.fromString("Short1"), Left(InvalidPassword("Password must be at least 8 characters")))
  }

  test("password without uppercase is rejected") {
    assertEquals(Password.fromString("securepass1"), Left(InvalidPassword("Password must contain at least one uppercase letter")))
  }

  test("password without lowercase is rejected") {
    assertEquals(Password.fromString("SECUREPASS1"), Left(InvalidPassword("Password must contain at least one lowercase letter")))
  }

  test("password without digit is rejected") {
    assertEquals(Password.fromString("SecurepassX"), Left(InvalidPassword("Password must contain at least one number")))
  }

  test("password of exactly 8 chars meeting all rules is accepted") {
    assert(Password.fromString("Passw0rd").isRight)
  }

  test("Password.value holds the original raw string") {
    assertEquals(Password.fromString("Securepass1").map(_.value), Right("Securepass1"))
  }
