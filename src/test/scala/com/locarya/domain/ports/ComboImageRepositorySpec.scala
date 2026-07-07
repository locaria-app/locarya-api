package com.locarya.domain.ports

import cats.effect.IO
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.helpers.InMemoryComboImageRepository
import munit.CatsEffectSuite

class ComboImageRepositorySpec extends CatsEffectSuite:

  private def makeRepo: IO[ComboImageRepository[IO]] =
    InMemoryComboImageRepository.make[IO]

  private val comboId = ComboId.generate
  private val url1    = "https://example.com/img1.jpg"
  private val url2    = "https://example.com/img2.jpg"

  private def images(id: ComboId = comboId, urls: List[String] = List(url1, url2)): List[ComboImage] =
    ComboImage.create(id, urls).toOption.get

  test("findByComboId returns empty list when no images exist") {
    for
      repo   <- makeRepo
      result <- repo.findByComboId(comboId)
    yield assertEquals(result, Nil)
  }

  test("create stores a single image and findByComboId retrieves it") {
    val img = images(urls = List(url1)).head
    for
      repo   <- makeRepo
      stored <- repo.create(img)
      found  <- repo.findByComboId(comboId)
    yield
      assertEquals(stored, img)
      assertEquals(found, List(img))
  }

  test("findByComboId returns all images for a combo") {
    val imgs = images()
    for
      repo  <- makeRepo
      _     <- imgs.traverse(repo.create)
      found <- repo.findByComboId(comboId)
    yield assertEquals(found.map(_.id).toSet, imgs.map(_.id).toSet)
  }

  test("findByComboId does not return images belonging to another combo") {
    val otherId   = ComboId.generate
    val myImages  = images(comboId)
    val otherImgs = images(otherId, List(url2))
    for
      repo  <- makeRepo
      _     <- myImages.traverse(repo.create)
      _     <- otherImgs.traverse(repo.create)
      found <- repo.findByComboId(comboId)
    yield assertEquals(found.map(_.id).toSet, myImages.map(_.id).toSet)
  }

  test("replaceImages removes old images and stores new ones") {
    val oldImages = images(urls = List(url1))
    val newImages = images(urls = List(url2))
    for
      repo  <- makeRepo
      _     <- oldImages.traverse(repo.create)
      _     <- repo.replaceImages(comboId, newImages)
      found <- repo.findByComboId(comboId)
    yield
      assertEquals(found.map(_.imageUrl.value).toSet, Set(url2))
      assert(!found.exists(_.imageUrl.value == url1))
  }

  test("replaceImages with empty list removes all images") {
    val imgs = images()
    for
      repo  <- makeRepo
      _     <- imgs.traverse(repo.create)
      _     <- repo.replaceImages(comboId, List.empty)
      found <- repo.findByComboId(comboId)
    yield assertEquals(found, Nil)
  }
