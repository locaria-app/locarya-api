package com.locarya.domain.ports

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryItemImageRepository
import munit.CatsEffectSuite

class ItemImageRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ItemImageRepository[IO]] =
    InMemoryItemImageRepository.make[IO]

  private val itemId = ItemId.generate
  private val url1   = "https://example.com/img1.jpg"
  private val url2   = "https://example.com/img2.jpg"

  private def images(id: ItemId = itemId, urls: List[String] = List(url1, url2)): List[ItemImage] =
    ItemImage.create(id, urls).toOption.get

  test("findByItemId returns empty list when no images exist") {
    for
      repo   <- makeRepo
      result <- repo.findByItemId(itemId)
    yield assertEquals(result, Nil)
  }

  test("create stores a single image and findByItemId retrieves it") {
    val img = images(urls = List(url1)).head
    for
      repo   <- makeRepo
      stored <- repo.create(img)
      found  <- repo.findByItemId(itemId)
    yield
      assertEquals(stored, img)
      assertEquals(found, List(img))
  }

  test("findByItemId returns all images for an item") {
    val imgs = images()
    for
      repo  <- makeRepo
      _     <- imgs.traverse(repo.create)
      found <- repo.findByItemId(itemId)
    yield assertEquals(found.map(_.id).toSet, imgs.map(_.id).toSet)
  }

  test("findByItemId does not return images belonging to another item") {
    val otherId   = ItemId.generate
    val myImages  = images(itemId)
    val otherImgs = images(otherId, List(url2))
    for
      repo  <- makeRepo
      _     <- myImages.traverse(repo.create)
      _     <- otherImgs.traverse(repo.create)
      found <- repo.findByItemId(itemId)
    yield assertEquals(found.map(_.id).toSet, myImages.map(_.id).toSet)
  }

  test("replaceImages removes old images and stores new ones") {
    val oldImages = images(urls = List(url1))
    val newImages = images(urls = List(url2))
    for
      repo  <- makeRepo
      _     <- oldImages.traverse(repo.create)
      _     <- repo.replaceImages(itemId, newImages)
      found <- repo.findByItemId(itemId)
    yield
      assertEquals(found.map(_.imageUrl.value).toSet, Set(url2))
      assert(!found.exists(_.imageUrl.value == url1))
  }

  test("replaceImages with empty list removes all images") {
    val imgs = images()
    for
      repo  <- makeRepo
      _     <- imgs.traverse(repo.create)
      _     <- repo.replaceImages(itemId, List.empty)
      found <- repo.findByItemId(itemId)
    yield assertEquals(found, Nil)
  }

  // ── findByItemIds ────────────────────────────────────────────────────────────

  test("findByItemIds returns images grouped by itemId") {
    val id1    = ItemId.generate
    val id2    = ItemId.generate
    val imgs1  = images(id1, List(url1, url2))
    val imgs2  = images(id2, List(url2))
    for
      repo   <- makeRepo
      _      <- imgs1.traverse(repo.create)
      _      <- imgs2.traverse(repo.create)
      result <- repo.findByItemIds(List(id1, id2))
    yield
      assertEquals(result(id1).map(_.imageUrl.value).toSet, Set(url1, url2))
      assertEquals(result(id2).map(_.imageUrl.value).toSet, Set(url2))
  }

  test("findByItemIds images are ordered by displayOrder ascending") {
    val id    = ItemId.generate
    val imgs  = images(id, List(url1, url2))
    for
      repo   <- makeRepo
      _      <- imgs.traverse(repo.create)
      result <- repo.findByItemIds(List(id))
    yield
      val found = result(id)
      assertEquals(found.map(_.displayOrder), found.sortBy(_.displayOrder).map(_.displayOrder))
  }

  test("findByItemIds does not include images for absent item ids") {
    val id1    = ItemId.generate
    val absent = ItemId.generate
    val imgs   = images(id1, List(url1))
    for
      repo   <- makeRepo
      _      <- imgs.traverse(repo.create)
      result <- repo.findByItemIds(List(id1, absent))
    yield
      assert(result.contains(id1), "id1 should be in result")
      assert(!result.contains(absent), "absent id should not be in result")
  }

  test("findByItemIds returns empty map when itemIds is empty") {
    val imgs = images()
    for
      repo   <- makeRepo
      _      <- imgs.traverse(repo.create)
      result <- repo.findByItemIds(List.empty)
    yield assertEquals(result, Map.empty)
  }
