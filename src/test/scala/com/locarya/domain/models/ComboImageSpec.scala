package com.locarya.domain.models

import munit.FunSuite

class ComboImageSpec extends FunSuite:

  private val comboId = ComboId.generate
  private val url1    = "https://example.com/image1.jpg"
  private val url2    = "https://example.com/image2.jpg"
  private val url3    = "https://example.com/image3.jpg"

  test("create with single URL produces one image that is primary at displayOrder 1") {
    val result = ComboImage.create(comboId, List(url1))
    assert(result.isRight, s"Expected Right but got $result")
    result.foreach { images =>
      assertEquals(images.size, 1)
      assertEquals(images.head.isPrimary, true)
      assertEquals(images.head.displayOrder, 1)
      assertEquals(images.head.imageUrl.value, url1)
      assertEquals(images.head.comboId, comboId)
    }
  }

  test("create with multiple URLs sets only first as primary") {
    val result = ComboImage.create(comboId, List(url1, url2, url3))
    assert(result.isRight, s"Expected Right but got $result")
    result.foreach { images =>
      assertEquals(images.size, 3)
      assertEquals(images.count(_.isPrimary), 1)
      assertEquals(images.head.isPrimary, true)
      assert(!images.tail.exists(_.isPrimary))
    }
  }

  test("create with multiple URLs assigns ascending displayOrder starting at 1") {
    val result = ComboImage.create(comboId, List(url1, url2, url3))
    assert(result.isRight)
    result.foreach { images =>
      assertEquals(images.map(_.displayOrder), List(1, 2, 3))
    }
  }

  test("create assigns distinct ComboImageIds") {
    val result = ComboImage.create(comboId, List(url1, url2))
    assert(result.isRight)
    result.foreach { images =>
      assertEquals(images.map(_.id).distinct.size, 2)
    }
  }

  test("create with empty list fails with InvalidComboImage") {
    val result = ComboImage.create(comboId, List.empty)
    assert(result.isLeft, "Expected Left for empty URL list")
    result.left.foreach { err =>
      assert(err.isInstanceOf[InvalidComboImage], s"Expected InvalidComboImage but got $err")
    }
  }

  test("create with more than 5 URLs fails with InvalidComboImage") {
    val urls = (1 to 6).map(i => s"https://example.com/img$i.jpg").toList
    val result = ComboImage.create(comboId, urls)
    assert(result.isLeft, "Expected Left for > 5 URLs")
    result.left.foreach { err =>
      assert(err.isInstanceOf[InvalidComboImage], s"Expected InvalidComboImage but got $err")
    }
  }

  test("create with exactly 5 URLs succeeds") {
    val urls = (1 to 5).map(i => s"https://example.com/img$i.jpg").toList
    val result = ComboImage.create(comboId, urls)
    assert(result.isRight, s"Expected Right for 5 URLs but got $result")
    result.foreach(images => assertEquals(images.size, 5))
  }

  test("create with an invalid URL fails with validation error") {
    val result = ComboImage.create(comboId, List("not-a-url"))
    assert(result.isLeft, "Expected Left for invalid URL")
  }
