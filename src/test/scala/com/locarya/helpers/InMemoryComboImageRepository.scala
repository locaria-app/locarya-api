package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ComboImageRepository

final class InMemoryComboImageRepository[F[_]: Async] private (
  state: Ref[F, Map[ComboImageId, ComboImage]]
) extends ComboImageRepository[F]:

  def create(image: ComboImage): F[ComboImage] =
    state.modify { store =>
      (store + (image.id -> image), image.pure[F])
    }.flatten

  def findByComboId(comboId: ComboId): F[List[ComboImage]] =
    state.get.map(_.values.filter(_.comboId == comboId).toList.sortBy(_.displayOrder))

  def replaceImages(comboId: ComboId, images: List[ComboImage]): F[Unit] =
    state.update { store =>
      val without = store.filter { case (_, img) => img.comboId != comboId }
      without ++ images.map(img => img.id -> img)
    }

object InMemoryComboImageRepository:
  def make[F[_]: Async]: F[InMemoryComboImageRepository[F]] =
    Ref.of[F, Map[ComboImageId, ComboImage]](Map.empty).map(new InMemoryComboImageRepository(_))
