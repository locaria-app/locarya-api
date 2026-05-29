package com.locarya.core.domain

import munit.FunSuite

class URLSpec extends FunSuite {

  test("create URL with valid http URL succeeds") {
    val result = URL.fromString("http://example.com")

    assert(result.isRight, "Should create URL with valid http URL")
    result.foreach { url =>
      assertEquals(url.value, "http://example.com")
    }
  }

  test("create URL with valid https URL succeeds") {
    val result = URL.fromString("https://secure.example.com/path")

    assert(result.isRight, "Should create URL with valid https URL")
    result.foreach { url =>
      assertEquals(url.value, "https://secure.example.com/path")
    }
  }

  test("create URL with empty string fails") {
    val result = URL.fromString("")

    assert(result.isLeft, "Should fail to create URL with empty string")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidURL], "Should return InvalidURL error")
    }
  }

  test("create URL without scheme fails") {
    val result = URL.fromString("example.com")

    assert(result.isLeft, "Should fail to create URL without scheme")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidURL], "Should return InvalidURL error")
    }
  }

  test("create URL with invalid scheme fails") {
    val result = URL.fromString("ftp://example.com")

    assert(result.isLeft, "Should fail to create URL with non-http/https scheme")
    result.left.foreach { error =>
      assert(error.isInstanceOf[InvalidURL], "Should return InvalidURL error")
    }
  }
}
