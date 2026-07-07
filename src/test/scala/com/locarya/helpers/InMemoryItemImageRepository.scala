package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.ItemImageRepository

final class InMemoryItemImageRepository[F[_]: Async] private (
  state: Ref[F, Map[ItemImageId, ItemImage]]
) extends ItemImageRepository[F]:

  def create(image: ItemImage): F[ItemImage] =
    state.modify { store =>
      (store + (image.id -> image), image.pure[F])
    }.flatten

  def findByItemId(itemId: ItemId): F[List[ItemImage]] =
    state.get.map(_.values.filter(_.itemId == itemId).toList.sortBy(_.displayOrder))

  def findByItemIds(itemIds: List[ItemId]): F[Map[ItemId, List[ItemImage]]] =
    if itemIds.isEmpty then Map.empty[ItemId, List[ItemImage]].pure[F]
    else
      state.get.map { store =>
        val matching = store.values.filter(img => itemIds.contains(img.itemId)).toList
        matching.groupBy(_.itemId).view.mapValues(_.sortBy(_.displayOrder)).toMap
      }

  def replaceImages(itemId: ItemId, images: List[ItemImage]): F[Unit] =
    state.update { store =>
      val without = store.filter { case (_, img) => img.itemId != itemId }
      without ++ images.map(img => img.id -> img)
    }

object InMemoryItemImageRepository:
  def make[F[_]: Async]: F[InMemoryItemImageRepository[F]] =
    Ref.of[F, Map[ItemImageId, ItemImage]](Map.empty).map(new InMemoryItemImageRepository(_))
