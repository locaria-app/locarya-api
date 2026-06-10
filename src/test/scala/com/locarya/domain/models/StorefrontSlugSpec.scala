package com.locarya.domain.models

import munit.FunSuite

class StorefrontSlugSpec extends FunSuite:

  private val SlugPattern = "^[a-z0-9][a-z0-9-]*-[0-9a-f]{6}$".r

  test("generate produces slug matching <slugified-name>-<6hexchars> pattern") {
    val slug = StorefrontSlug.generate("Brinquedos Infláveis ME")
    assert(
      SlugPattern.matches(slug.value),
      s"Slug '${slug.value}' does not match expected pattern"
    )
  }

  test("generate slugifies accented characters") {
    val slug = StorefrontSlug.generate("Ação & Festa")
    assert(slug.value.startsWith("acao"), s"Expected 'acao...' prefix but got '${slug.value}'")
  }

  test("generate two slugs for same name produces different values") {
    val a = StorefrontSlug.generate("Brinquedos")
    val b = StorefrontSlug.generate("Brinquedos")
    assertNotEquals(a.value, b.value, "Two generate calls must produce different slugs")
  }

  test("fromString accepts a valid slug") {
    val result = StorefrontSlug.fromString("brinquedos-inflaveis-me-abc123")
    assertEquals(result.map(_.value), Right("brinquedos-inflaveis-me-abc123"))
  }

  test("fromString rejects empty string") {
    val result = StorefrontSlug.fromString("")
    assertEquals(result, Left(InvalidStorefrontSlug("Storefront slug cannot be empty")))
  }

  test("fromString rejects blank string") {
    val result = StorefrontSlug.fromString("   ")
    assert(result.isLeft)
  }

  test("fromString trims whitespace") {
    val result = StorefrontSlug.fromString("  test-slug-abc123  ")
    assertEquals(result.map(_.value), Right("test-slug-abc123"))
  }
