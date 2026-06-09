package com.locarya.domain.models

import munit.FunSuite

class EmailSpec extends FunSuite {

  test("create Email with valid format succeeds") {
    val result = Email.fromString("user@example.com")

    assert(result.isRight, "Should create Email with valid format")
    result.foreach { email =>
      assertEquals(email.value, "user@example.com")
    }
  }

  test("create Email without @ fails") {
    val result = Email.fromString("notanemail")

    assert(result.isLeft, "Should fail when email has no @")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidEmail], "Should return InvalidEmail error")
    }
  }

  test("create Email with empty string fails") {
    val result = Email.fromString("")

    assert(result.isLeft, "Should fail when email is empty")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidEmail], "Should return InvalidEmail error")
    }
  }

  test("create Email with missing domain fails") {
    val result = Email.fromString("user@")

    assert(result.isLeft, "Should fail when domain is missing")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidEmail], "Should return InvalidEmail error")
    }
  }

  test("Email normalizes to lowercase") {
    val result = Email.fromString("User@Example.COM")

    assert(result.isRight, "Should succeed with mixed case email")
    result.foreach { email =>
      assertEquals(email.value, "user@example.com", "Should normalize to lowercase")
    }
  }

  test("Email trims whitespace") {
    val result = Email.fromString("  user@example.com  ")

    assert(result.isRight, "Should succeed with whitespace")
    result.foreach { email =>
      assertEquals(email.value, "user@example.com", "Should trim whitespace")
    }
  }
}
