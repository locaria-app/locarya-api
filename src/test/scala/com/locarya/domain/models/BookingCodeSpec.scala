package com.locarya.domain.models

import munit.FunSuite

class BookingCodeSpec extends FunSuite:

  test("fromString accepts a valid LCR-XXXXXX code") {
    val result = BookingCode.fromString("LCR-A3F1C2")
    assert(result.isRight, s"Expected Right, got $result")
    result.foreach(bc => assertEquals(bc.value, "LCR-A3F1C2"))
  }

  test("fromString rejects lowercase suffix characters") {
    val result = BookingCode.fromString("LCR-a3f1c2")
    assert(result.isLeft, "Expected Left for lowercase chars")
    result.left.foreach { err =>
      assert(err.isInstanceOf[InvalidBookingCode], s"Expected InvalidBookingCode, got $err")
    }
  }

  test("fromString rejects too-short suffix (4 chars)") {
    assert(BookingCode.fromString("LCR-A3F1").isLeft)
  }

  test("fromString rejects too-long suffix (7 chars)") {
    assert(BookingCode.fromString("LCR-A3F1C2X").isLeft)
  }

  test("fromString rejects wrong prefix") {
    assert(BookingCode.fromString("XYZ-A3F1C2").isLeft)
  }

  test("fromString rejects empty string") {
    assert(BookingCode.fromString("").isLeft)
  }

  test("fromString rejects code without hyphen separator") {
    assert(BookingCode.fromString("LCRA3F1C2").isLeft)
  }

  test("generate produces a code matching LCR-[A-Z0-9]{6}") {
    val code = BookingCode.generate
    val pattern = """^LCR-[A-Z0-9]{6}$""".r
    assert(pattern.matches(code.value), s"Generated code '${code.value}' does not match pattern")
  }

  test("generate produces different codes on consecutive calls (opaque — not sequential)") {
    val a = BookingCode.generate
    val b = BookingCode.generate
    assertNotEquals(a.value, b.value, "Expected two consecutive generate() calls to differ")
  }

  test("fromString round-trips a generated code") {
    val generated = BookingCode.generate
    val parsed    = BookingCode.fromString(generated.value)
    assert(parsed.isRight, s"Expected generated code to round-trip; got $parsed")
    parsed.foreach(p => assertEquals(p.value, generated.value))
  }
